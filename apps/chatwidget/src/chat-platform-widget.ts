import { LitElement, css, html } from 'lit';
import { live } from 'lit/directives/live.js';
import {
  ApiError,
  CONNECTION_LABEL,
  conversationChannel,
  createChatApi,
  createRealtime,
  mergeMessages,
  type ChatMessage,
  type ConnectionState,
  type Conversation,
  type Realtime,
} from '@chat-platform/chat-client';

/** LOCAL POC — NOT REAL AUTHENTICATION: the widget always acts as the hardcoded demo visitor. */
const VISITOR = 'userone' as const;

const env = import.meta.env as Record<string, string | undefined>;

const time = (iso: string) => new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/**
 * `<chat-platform-widget>` — visitor chat panel. History comes from the REST API (PostgreSQL); live updates arrive
 * from Centrifugo and are merged by message id. The widget never publishes to Centrifugo.
 *
 * Attributes: `api-base-url` (default http://localhost:8080), `realtime-url` (default
 * ws://localhost:8000/connection/websocket). Both must be browser-reachable URLs.
 */
export class ChatPlatformWidget extends LitElement {
  static override properties = {
    apiBaseUrl: { type: String, attribute: 'api-base-url' },
    realtimeUrl: { type: String, attribute: 'realtime-url' },
    conversation: { state: true },
    messages: { state: true },
    connection: { state: true },
    draft: { state: true },
    sending: { state: true },
    error: { state: true },
    notice: { state: true },
    subscribed: { state: true },
  };

  declare apiBaseUrl: string;
  declare realtimeUrl: string;
  declare conversation: Conversation | null;
  declare messages: ChatMessage[];
  declare connection: ConnectionState;
  declare draft: string;
  declare sending: boolean;
  declare error: string;
  declare notice: string;
  declare subscribed: boolean;

  private realtime: Realtime | null = null;
  private cleanups: Array<() => void> = [];

  constructor() {
    super();
    this.apiBaseUrl = env['VITE_PUBLIC_API_BASE_URL'] ?? 'http://localhost:8080';
    this.realtimeUrl = env['VITE_PUBLIC_CENTRIFUGO_WS_URL'] ?? 'ws://localhost:8000/connection/websocket';
    this.conversation = null;
    this.messages = [];
    this.connection = 'connecting';
    this.draft = '';
    this.sending = false;
    this.error = '';
    this.notice = '';
    this.subscribed = false;
  }

  private get api() {
    return createChatApi(this.apiBaseUrl);
  }

  override connectedCallback() {
    super.connectedCallback();
    void this.start();
  }

  override disconnectedCallback() {
    super.disconnectedCallback();
    this.cleanups.forEach((c) => c());
    this.cleanups = [];
    this.realtime?.disconnect();
    this.realtime = null;
  }

  private async start() {
    try {
      this.conversation = await this.api.ensureConversation();
      await this.reloadHistory();
    } catch (e) {
      this.error = `Could not load the conversation: ${(e as Error).message}`;
      this.connection = 'offline';
      return;
    }
    const conversationId = this.conversation.id;
    this.realtime = createRealtime({
      url: this.realtimeUrl,
      api: this.api,
      actorId: VISITOR,
      onState: (s) => {
        this.connection = s;
        if (s !== 'live') this.subscribed = false;
      },
    });
    this.cleanups.push(
      this.realtime.subscribe(conversationChannel(conversationId), {
        onEvent: (event) => {
          if (event.type === 'message.created' && event.conversationId === conversationId) {
            this.messages = mergeMessages(this.messages, [event.message]);
          }
        },
        // After (re)subscribing, reload from the API so nothing published while offline is missed.
        onSubscribed: () => {
          this.subscribed = true;
          void this.reloadHistory().catch(() => undefined);
        },
        onError: (message) => (this.error = `Live updates unavailable: ${message}`),
      }),
    );
    this.realtime.connect();
  }

  protected override updated(changed: Map<string, unknown>) {
    // Keep the newest message in view.
    if (changed.has('messages')) {
      const log = this.renderRoot.querySelector('ol');
      if (log) log.scrollTop = log.scrollHeight;
    }
  }

  private async reloadHistory() {
    if (!this.conversation) return;
    const history = await this.api.listMessages(this.conversation.id, VISITOR);
    this.messages = mergeMessages(this.messages, history);
  }

  private async send(e: SubmitEvent) {
    e.preventDefault();
    const text = this.draft.trim();
    if (!text) {
      this.notice = 'Type a message first.';
      return;
    }
    if (!this.conversation || this.sending) return;
    this.sending = true;
    this.error = '';
    this.notice = '';
    try {
      const result = await this.api.sendMessage(this.conversation.id, VISITOR, text);
      this.messages = mergeMessages(this.messages, [result.message]);
      this.draft = '';
      if (result.realtimeDelivery === 'FAILED') {
        this.notice = 'Message saved, but live delivery failed. The agent will see it after reloading.';
      }
    } catch (err) {
      const detail = err instanceof ApiError ? err.message : 'unexpected error';
      this.error = `Message not sent: ${detail}`;
    } finally {
      this.sending = false;
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
    .conn {
      font-size: var(--cp-text-sm);
      padding: 0 var(--cp-spacing-sm);
      border-radius: var(--cp-radius-badge);
      background: var(--cp-color-surface);
      color: var(--cp-color-text);
    }
    .conn[data-state='live'] {
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
    .AGENT {
      align-self: flex-start;
      background: var(--cp-color-background);
    }
    .VISITOR {
      align-self: flex-end;
      background: var(--cp-color-primary);
      color: var(--cp-color-on-primary);
    }
    .empty {
      color: var(--cp-color-text-muted);
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

  override render() {
    return html`
      <section class="panel" aria-labelledby="title">
        <header>
          <div>
            <h2 id="title">Chat with us</h2>
            <span class="who" data-testid="visitor-id">Demo visitor: ${VISITOR}</span>
          </div>
          <span class="conn" data-testid="connection-state" data-state=${this.connection} role="status">
            ${CONNECTION_LABEL[this.connection]}
          </span>
        </header>
        <ol
          role="log"
          aria-live="polite"
          aria-label="Conversation"
          data-testid="widget-messages"
          data-conversation-id=${this.conversation?.id ?? ''}
          data-subscribed=${String(this.subscribed)}
        >
          ${this.messages.length === 0
            ? html`<li class="empty" data-testid="greeting">Hi! Send a message and an agent will reply.</li>`
            : this.messages.map(
                (m) => html`<li class=${m.senderRole} data-testid="message" data-message-id=${m.id}>
                  <span class="meta">${m.senderRole === 'VISITOR' ? 'You' : m.senderId} · ${time(m.createdAt)}</span>
                  <span data-testid="message-body">${m.body}</span>
                </li>`,
              )}
        </ol>
        ${this.error ? html`<p class="msg error" role="alert" data-testid="widget-error">${this.error}</p>` : ''}
        ${this.notice ? html`<p class="msg warn" role="status" data-testid="widget-notice">${this.notice}</p>` : ''}
        <form @submit=${this.send}>
          <label class="sr-only" for="composer">Message</label>
          <input
            id="composer"
            autocomplete="off"
            maxlength="4000"
            placeholder="Type a message…"
            .value=${live(this.draft)}
            @input=${(e: InputEvent) => (this.draft = (e.target as HTMLInputElement).value)}
          />
          <button type="submit" ?disabled=${this.sending || !this.conversation}>
            ${this.sending ? 'Sending…' : 'Send'}
          </button>
        </form>
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
