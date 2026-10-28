import { LitElement, css, html } from 'lit';
import { live } from 'lit/directives/live.js';
import {
  ApiError,
  CONNECTION_LABEL,
  MAX_MESSAGE_LENGTH,
  conversationChannel,
  createConversationSync,
  createPendingQueue,
  createRealtime,
  createTypingEmitter,
  createTypingReceiver,
  createWidgetApi,
  createWidgetSessionStore,
  ensureSession,
  type ChatEvent,
  type ConnectionState,
  type ConversationSync,
  type ConversationView,
  type MessageView,
  type PendingMessage,
  type PendingQueue,
  type Realtime,
  type WidgetApi,
} from '@chat-platform/chat-client';

const env = import.meta.env as Record<string, string | undefined>;

const PRESENCE_REFRESH_MS = 30_000;
const READ_DEBOUNCE_MS = 1_000;

const time = (iso: string) => new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/**
 * `<chat-platform-widget>` — visitor chat panel. History and catch-up come from the REST API (PostgreSQL); live updates
 * arrive from Centrifugo through the shared conversation sync. The widget never publishes to Centrifugo and renders
 * message bodies as text only.
 *
 * Attributes: `api-base-url` (default http://localhost:8080), `realtime-url` (default
 * ws://localhost:8000/connection/websocket), `widget-key` (default VITE_PUBLIC_WIDGET_KEY or wk_local_demo),
 * `external-id` and `display-name` (optional contact identity). URLs must be browser-reachable.
 */
export class ChatPlatformWidget extends LitElement {
  static override properties = {
    apiBaseUrl: { type: String, attribute: 'api-base-url' },
    realtimeUrl: { type: String, attribute: 'realtime-url' },
    widgetKey: { type: String, attribute: 'widget-key' },
    externalId: { type: String, attribute: 'external-id' },
    displayName: { type: String, attribute: 'display-name' },
    conversation: { state: true },
    messages: { state: true },
    pending: { state: true },
    connection: { state: true },
    draft: { state: true },
    error: { state: true },
    notice: { state: true },
    subscribed: { state: true },
    agentTyping: { state: true },
    closed: { state: true },
  };

  declare apiBaseUrl: string;
  declare realtimeUrl: string;
  declare widgetKey: string;
  declare externalId: string | undefined;
  declare displayName: string | undefined;
  declare conversation: ConversationView | null;
  declare messages: MessageView[];
  declare pending: PendingMessage[];
  declare connection: ConnectionState;
  declare draft: string;
  declare error: string;
  declare notice: string;
  declare subscribed: boolean;
  declare agentTyping: boolean;
  /** True once the conversation is archived (409 CONVERSATION_ARCHIVED or `archivedAt` set). */
  declare closed: boolean;

  private token: string | null = null;
  private api: WidgetApi | null = null;
  private realtime: Realtime | null = null;
  private sync: ConversationSync | null = null;
  private queue: PendingQueue | null = null;
  private typingEmitter: ReturnType<typeof createTypingEmitter> | null = null;
  private typingReceiver: ReturnType<typeof createTypingReceiver> | null = null;
  private cleanups: Array<() => void> = [];
  private presenceTimer: ReturnType<typeof setInterval> | undefined;
  private readTimer: ReturnType<typeof setTimeout> | undefined;
  private lastRead = 0;

  constructor() {
    super();
    this.apiBaseUrl = env['VITE_PUBLIC_API_BASE_URL'] ?? 'http://localhost:8080';
    this.realtimeUrl = env['VITE_PUBLIC_CENTRIFUGO_WS_URL'] ?? 'ws://localhost:8000/connection/websocket';
    this.widgetKey = env['VITE_PUBLIC_WIDGET_KEY'] ?? 'wk_local_demo';
    this.externalId = undefined;
    this.displayName = undefined;
    this.conversation = null;
    this.messages = [];
    this.pending = [];
    this.connection = 'connecting';
    this.draft = '';
    this.error = '';
    this.notice = '';
    this.subscribed = false;
    this.agentTyping = false;
    this.closed = false;
  }

  override connectedCallback() {
    super.connectedCallback();
    void this.start();
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this.unbind();
    clearInterval(this.presenceTimer);
    this.realtime?.disconnect();
    this.realtime = null;
  }

  private async start() {
    const api = createWidgetApi(this.apiBaseUrl, () => this.token);
    this.api = api;
    let view: ConversationView;
    try {
      const session = await ensureSession(api, createWidgetSessionStore(this.widgetKey), this.widgetKey, {
        externalId: this.externalId,
        displayName: this.displayName,
      });
      this.token = session.sessionToken;
      view = await api.startOrResume();
    } catch (e) {
      this.error = `Could not load the conversation: ${(e as Error).message}`;
      this.connection = 'offline';
      return;
    }
    this.realtime = createRealtime({
      url: this.realtimeUrl,
      getConnectionToken: async () => (await api.connectionToken()).token,
      getSubscriptionToken: async (channel) => (await api.subscriptionToken(channel)).token,
      onState: (s) => {
        this.connection = s;
        if (s !== 'live') this.subscribed = false;
      },
    });
    this.bind(view);
    this.realtime.connect();
    this.presenceTimer = setInterval(() => void this.refreshConversation(), PRESENCE_REFRESH_MS);
  }

  /** (Re)wires sync, pending queue, typing and the channel subscription to one conversation. */
  private bind(view: ConversationView) {
    this.unbind();
    const api = this.api as WidgetApi;
    const id = view.id;
    this.conversation = view;
    this.messages = [];
    this.pending = [];
    this.closed = view.archivedAt !== null;
    this.lastRead = 0;
    this.error = '';
    this.notice = '';

    const sync = createConversationSync({
      conversationId: id,
      fetchLatest: () => api.listMessages(id, { limit: 50 }),
      fetchAfter: (after) => api.listMessages(id, { after, limit: 200 }),
      fetchConversation: () => api.getConversation(id),
      onMessages: (messages) => {
        this.messages = messages;
        this.scheduleRead();
      },
      onConversationUpdated: (updated) => {
        this.conversation = updated;
        this.closed = updated.archivedAt !== null;
      },
    });
    this.sync = sync;

    const queue = createPendingQueue({
      send: async (clientMessageId, body) => {
        try {
          const message = await api.sendMessage(id, { clientMessageId, body });
          // Same path as a live event: deduped by sequence if the event already arrived first.
          void sync.handleEvent(this.envelope(message));
          return message;
        } catch (err) {
          if (err instanceof ApiError && err.code === 'CONVERSATION_ARCHIVED') this.closed = true;
          throw err;
        }
      },
    });
    this.queue = queue;
    this.cleanups.push(queue.subscribe((items) => (this.pending = items)));

    this.typingEmitter = createTypingEmitter({ send: (typing) => api.typing(id, typing) });
    this.typingReceiver = createTypingReceiver({ onChange: (typing) => (this.agentTyping = typing) });

    this.cleanups.push(
      (this.realtime as Realtime).subscribe(conversationChannel(id), {
        onEvent: (event) => this.onEvent(id, event),
        // Recovery is only an optimisation: every (re)subscribe reconciles from the API.
        onSubscribed: (ctx) => {
          this.subscribed = true;
          void sync.handleSubscribed(ctx).catch(() => undefined);
        },
        onError: (message) => (this.error = `Live updates unavailable: ${message}`),
      }),
    );
  }

  private unbind() {
    this.cleanups.forEach((c) => c());
    this.cleanups = [];
    this.typingEmitter?.stop();
    this.typingReceiver?.reset();
    clearTimeout(this.readTimer);
    this.sync = null;
    this.queue = null;
  }

  private envelope(message: MessageView): ChatEvent {
    return {
      eventId: `sent:${message.id}`,
      eventType: 'message.created',
      schemaVersion: 1,
      tenantId: this.conversation?.tenantId ?? '',
      conversationId: message.conversationId,
      occurredAt: message.createdAt,
      data: { message },
    };
  }

  private onEvent(conversationId: string, event: ChatEvent) {
    if (event.conversationId !== conversationId) return;
    if (event.eventType === 'typing') {
      if (event.data.participantType === 'AGENT') this.typingReceiver?.handle(event);
      return;
    }
    if (event.eventType === 'message.created') {
      const { clientMessageId } = event.data.message;
      if (clientMessageId) this.queue?.markSent(clientMessageId, event.data.message);
    }
    void this.sync?.handleEvent(event);
    if (event.eventType === 'conversation.updated') void this.refreshConversation();
  }

  /** Per-caller fields (`agentOnline`) are only on the GET, so presence is refreshed from the API. */
  private async refreshConversation() {
    if (!this.api || !this.conversation) return;
    try {
      const view = await this.api.getConversation(this.conversation.id);
      this.conversation = view;
      this.closed = view.archivedAt !== null;
    } catch {
      // keep the last known state; the next tick retries
    }
  }

  private scheduleRead() {
    const sync = this.sync;
    const conversation = this.conversation;
    if (!sync || !conversation || document.visibilityState !== 'visible') return;
    if (sync.lastSequence() <= this.lastRead) return;
    clearTimeout(this.readTimer);
    this.readTimer = setTimeout(() => {
      const sequence = sync.lastSequence();
      if (sequence <= this.lastRead) return;
      const previous = this.lastRead;
      this.lastRead = sequence;
      this.api?.markRead(conversation.id, sequence).catch(() => (this.lastRead = previous));
    }, READ_DEBOUNCE_MS);
  }

  protected override updated(changed: Map<string, unknown>) {
    // Keep the newest message in view.
    if (changed.has('messages') || changed.has('pending')) {
      const log = this.renderRoot.querySelector('ol');
      if (log) log.scrollTop = log.scrollHeight;
    }
  }

  private send(e: SubmitEvent) {
    e.preventDefault();
    const text = this.draft.trim();
    if (!text) {
      this.notice = 'Type a message first.';
      return;
    }
    if (!this.queue || this.closed) return;
    this.error = '';
    this.notice = '';
    this.typingEmitter?.onSent();
    this.queue.enqueue(text);
    this.draft = '';
  }

  private onInput(e: InputEvent) {
    this.draft = (e.target as HTMLInputElement).value;
    this.typingEmitter?.onInput();
  }

  private async newConversation() {
    if (!this.api) return;
    try {
      this.bind(await this.api.startOrResume());
    } catch (err) {
      this.error = `Could not start a new conversation: ${(err as Error).message}`;
    }
  }

  static override styles = css`
    :host {
      display: block;
      max-width: 380px;
      font-family: var(--cp-font-family);
      color: var(--cp-color-text);
    }
    .panel {
      display: flex;
      flex-direction: column;
      background: var(--cp-color-surface);
      border: var(--cp-border);
      border-radius: var(--cp-radius-card);
      overflow: hidden;
    }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--cp-spacing-sm);
      padding: var(--cp-spacing-sm) var(--cp-spacing-md);
      background: var(--cp-color-primary);
      color: var(--cp-color-on-primary);
    }
    h2 {
      margin: 0;
      font-size: var(--cp-text-md);
    }
    .who {
      font-size: var(--cp-text-sm);
    }
    .badges {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: var(--cp-spacing-xs, 2px);
    }
    .conn,
    .status,
    .presence {
      font-size: var(--cp-text-sm);
      padding: 0 var(--cp-spacing-sm);
      border-radius: var(--cp-radius-badge);
      background: var(--cp-color-surface);
      color: var(--cp-color-text);
    }
    .conn[data-state='live'],
    .presence[data-online='true'] {
      color: var(--cp-color-success);
    }
    .conn[data-state='offline'] {
      color: var(--cp-color-danger);
    }
    ol {
      list-style: none;
      margin: 0;
      padding: var(--cp-spacing-md);
      display: flex;
      flex-direction: column;
      gap: var(--cp-spacing-sm);
      min-height: 180px;
      max-height: 320px;
      overflow-y: auto;
    }
    li {
      padding: var(--cp-spacing-sm) var(--cp-spacing-md);
      border-radius: var(--cp-radius-card);
      max-width: 85%;
      font-size: var(--cp-text-sm);
      overflow-wrap: anywhere;
    }
    .meta {
      display: block;
      font-size: var(--cp-font-size-100);
      opacity: 0.85;
    }
    .AGENT,
    .BOT,
    .SYSTEM {
      align-self: flex-start;
      background: var(--cp-color-background);
    }
    .CONTACT {
      align-self: flex-end;
      background: var(--cp-color-primary);
      color: var(--cp-color-on-primary);
    }
    li[data-state='pending'] {
      opacity: 0.6;
    }
    li[data-state='failed'] {
      outline: 1px solid var(--cp-color-danger);
    }
    .empty,
    .typing {
      color: var(--cp-color-text-muted);
    }
    .typing {
      margin: 0;
      padding: 0 var(--cp-spacing-md);
      font-size: var(--cp-text-sm);
    }
    form {
      display: flex;
      gap: var(--cp-spacing-sm);
      padding: var(--cp-spacing-sm);
      border-top: var(--cp-border);
    }
    input {
      flex: 1;
      font: inherit;
      padding: var(--cp-spacing-sm);
      border: var(--cp-border);
      border-radius: var(--cp-radius-control);
      color: var(--cp-color-text);
      background: var(--cp-color-surface);
    }
    button {
      font: inherit;
      padding: var(--cp-spacing-sm) var(--cp-spacing-md);
      border: none;
      border-radius: var(--cp-radius-control);
      background: var(--cp-color-primary);
      color: var(--cp-color-on-primary);
      cursor: pointer;
    }
    li button {
      margin-top: var(--cp-spacing-sm);
      padding: 0 var(--cp-spacing-sm);
      background: var(--cp-color-surface);
      color: var(--cp-color-danger);
    }
    button:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
    input:focus-visible,
    button:focus-visible {
      outline: none;
      box-shadow: var(--cp-focus-ring);
    }
    .msg {
      margin: 0;
      padding: 0 var(--cp-spacing-md) var(--cp-spacing-sm);
      font-size: var(--cp-text-sm);
    }
    .error {
      color: var(--cp-color-danger);
    }
    .warn {
      color: var(--cp-color-warning);
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `;

  private renderMessage(m: MessageView) {
    return html`<li class=${m.senderType} data-testid="message" data-message-id=${m.id} data-sequence=${m.sequence} data-state="sent">
      <span class="meta">${m.senderType === 'CONTACT' ? 'You' : m.senderId} · ${time(m.createdAt)}</span>
      <span data-testid="message-body">${m.body}</span>
    </li>`;
  }

  private renderPending(p: PendingMessage) {
    return html`<li class="CONTACT" data-testid="message" data-client-message-id=${p.clientMessageId} data-state=${p.state}>
      <span class="meta">You</span>
      <span data-testid="message-body">${p.body}</span>
      ${p.state === 'failed'
        ? html`<span class="meta" data-testid="message-error">Not sent: ${p.error}</span>
            <button type="button" data-testid="retry" @click=${() => this.queue?.retry(p.clientMessageId)}>Retry</button>`
        : ''}
    </li>`;
  }

  override render() {
    const confirmed = new Set(this.messages.map((m) => m.clientMessageId).filter(Boolean));
    const unconfirmed = this.pending.filter((p) => !confirmed.has(p.clientMessageId));
    const online = this.conversation?.agentOnline;
    const presence = online === true ? 'Online' : online === false ? 'Offline' : 'Unknown';
    return html`
      <section class="panel" aria-labelledby="title">
        <header>
          <div>
            <h2 id="title">Chat with us</h2>
            <span class="who" data-testid="visitor-id">${this.externalId ? `Demo visitor: ${this.externalId}` : 'Visitor'}</span>
          </div>
          <div class="badges">
            <span class="conn" data-testid="connection-state" data-state=${this.connection} role="status">
              ${CONNECTION_LABEL[this.connection]}
            </span>
            ${this.conversation
              ? html`<span class="status" data-testid="conversation-status">
                    ${this.closed ? 'Closed' : this.conversation.status}
                  </span>
                  <span class="presence" data-testid="agent-presence" data-online=${String(online === true)}>
                    Agent: ${presence}
                  </span>`
              : ''}
          </div>
        </header>
        <ol
          role="log"
          aria-live="polite"
          aria-label="Conversation"
          data-testid="widget-messages"
          data-conversation-id=${this.conversation?.id ?? ''}
          data-subscribed=${String(this.subscribed)}
        >
          ${this.messages.length === 0 && unconfirmed.length === 0
            ? html`<li class="empty" data-testid="greeting">Hi! Send a message and an agent will reply.</li>`
            : html`${this.messages.map((m) => this.renderMessage(m))}${unconfirmed.map((p) => this.renderPending(p))}`}
        </ol>
        ${this.agentTyping ? html`<p class="typing" data-testid="typing-indicator" role="status">Agent is typing…</p>` : ''}
        ${this.error ? html`<p class="msg error" role="alert" data-testid="widget-error">${this.error}</p>` : ''}
        ${this.notice ? html`<p class="msg warn" role="status" data-testid="widget-notice">${this.notice}</p>` : ''}
        ${this.closed
          ? html`<form @submit=${(e: Event) => e.preventDefault()}>
              <button type="button" data-testid="new-conversation" @click=${this.newConversation}>Start a new conversation</button>
            </form>`
          : html`<form @submit=${this.send}>
              <label class="sr-only" for="composer">Message</label>
              <input
                id="composer"
                autocomplete="off"
                maxlength=${MAX_MESSAGE_LENGTH}
                placeholder="Type a message…"
                .value=${live(this.draft)}
                @input=${this.onInput}
              />
              <button type="submit" ?disabled=${!this.conversation}>Send</button>
            </form>`}
        <p class="msg" style="color: var(--cp-color-text-muted)">LOCAL POC — not real authentication.</p>
      </section>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'chat-platform-widget': ChatPlatformWidget;
  }
}
