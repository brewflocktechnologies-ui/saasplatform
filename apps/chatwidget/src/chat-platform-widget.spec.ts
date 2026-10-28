import { WIDGET_TAG } from './embed/v1';

// The real SDK opens a WebSocket; replace it with an inert fake that records subscriptions and their handlers.
type Handler = (ctx: unknown) => void;
const subscriptions: Array<{ channel: string; handlers: Record<string, Handler> }> = [];
vi.mock('centrifuge', () => ({
  Centrifuge: class {
    on() {
      return this;
    }
    connect = () => undefined;
    disconnect = () => undefined;
    removeSubscription = () => undefined;
    newSubscription(channel: string) {
      const handlers: Record<string, Handler> = {};
      subscriptions.push({ channel, handlers });
      return {
        on(name: string, handler: Handler) {
          handlers[name] = handler;
        },
        subscribe: () => undefined,
        unsubscribe: () => undefined,
      };
    }
  },
}));

const conversation = (overrides: Record<string, unknown> = {}) => ({
  id: 'c-1',
  tenantId: 't-1',
  inboxId: 'i-1',
  contactId: 'k-1',
  contactDisplayName: 'User One',
  status: 'OPEN',
  version: 1,
  lastSequence: 2,
  lastMessageAt: null,
  lastMessagePreview: null,
  assignment: { state: 'ASSIGNED', agentId: 'agentbob' },
  resolvedAt: null,
  archivedAt: null,
  purgedAt: null,
  createdAt: '2026-10-25T10:00:00Z',
  updatedAt: '2026-10-25T10:00:00Z',
  unreadCount: 0,
  agentOnline: true,
  ...overrides,
});
const message = (sequence: number, body: string, senderType = 'CONTACT', clientMessageId: string | null = null) => ({
  id: `m${sequence}`,
  conversationId: 'c-1',
  sequence,
  senderType,
  senderId: senderType === 'CONTACT' ? 'k-1' : 'agentbob',
  messageType: 'TEXT',
  body,
  clientMessageId,
  createdAt: `2026-10-25T10:00:0${sequence}Z`,
  editedAt: null,
  deletedAt: null,
});
const envelope = (eventType: string, data: unknown, eventId: string) => ({
  eventId,
  eventType,
  schemaVersion: 1,
  tenantId: 't-1',
  conversationId: 'c-1',
  occurredAt: '2026-10-25T10:00:09Z',
  data,
});
const json = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));

const posts = () =>
  (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls.filter(
    ([url, init]) => url.endsWith('/conversations/c-1/messages') && init?.method === 'POST',
  );

async function mount() {
  const el = document.createElement('chat-platform-widget');
  el.setAttribute('external-id', 'userone');
  document.body.append(el);
  const root = el.shadowRoot as ShadowRoot;
  await vi.waitFor(() => expect(subscriptions.length).toBeGreaterThan(0));
  subscriptions[0].handlers['subscribed']?.({ wasRecovering: false, recovered: false });
  await vi.waitFor(() => expect(root.querySelectorAll('[data-testid="message"]').length).toBeGreaterThan(0));
  return { el, root };
}

const bodies = (root: ShadowRoot) => [...root.querySelectorAll('[data-testid="message-body"]')].map((m) => m.textContent);

function type(root: ShadowRoot, value: string) {
  const input = root.querySelector('input') as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  return input;
}
const submit = (root: ShadowRoot) => root.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));

describe('<chat-platform-widget>', () => {
  let sendResponse: () => Promise<Response>;
  let view: ReturnType<typeof conversation>;

  beforeEach(() => {
    subscriptions.length = 0;
    localStorage.clear();
    view = conversation();
    sendResponse = () => json(message(3, 'Hello from the test', 'CONTACT', 'cid'), 201);
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url.endsWith('/api/v1/widget/sessions'))
          return json({
            sessionToken: 's',
            expiresAt: '2026-10-26T10:00:00Z',
            tenantId: 't-1',
            inbox: { id: 'i-1', name: 'Inbox' },
            contact: { id: 'k-1', displayName: 'User One' },
          });
        if (url.endsWith('/api/v1/widget/conversations') && init?.method === 'POST') return json(view);
        if (url.endsWith('/conversations/c-1/messages') && init?.method === 'POST') return sendResponse();
        if (url.includes('/conversations/c-1/messages')) {
          const afterSeq = new URL(url).searchParams.get('after');
          return json({ items: afterSeq ? [] : [message(1, 'earlier question'), message(2, 'agent answer', 'AGENT')], hasMore: false });
        }
        if (url.endsWith('/conversations/c-1')) return json(view);
        if (url.includes('/typing') || url.includes('/read')) return Promise.resolve(new Response(null, { status: 204 }));
        return json({ token: 't', expiresAt: '2026-10-25T10:05:00Z' });
      }),
    );
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('is registered by the v1 embed entry point', () => {
    expect(customElements.get(WIDGET_TAG)).toBeDefined();
  });

  it('rendersHistoryThenLiveMessageOnce', async () => {
    const { root } = await mount();
    expect(root.querySelector('[data-testid="visitor-id"]')?.textContent).toContain('userone');
    expect(bodies(root)).toEqual(['earlier question', 'agent answer']);
    expect(subscriptions.map((s) => s.channel)).toEqual(['chat:conversation:c-1']);
    expect(root.querySelector('[data-testid="agent-presence"]')?.textContent).toContain('Online');
    expect(root.querySelector('[data-testid="conversation-status"]')?.textContent).toContain('OPEN');

    const live = { data: envelope('message.created', { message: message(3, 'live reply', 'AGENT') }, 'e3') };
    subscriptions[0].handlers['publication'](live);
    subscriptions[0].handlers['publication'](live);
    await vi.waitFor(() => expect(bodies(root)).toEqual(['earlier question', 'agent answer', 'live reply']));
    const last = root.querySelectorAll('[data-testid="message"]')[2];
    expect(last.getAttribute('data-sequence')).toBe('3');
    expect(last.getAttribute('data-state')).toBe('sent');
  });

  it('failedSendShowsRetryAndRetryReusesId', async () => {
    sendResponse = () => json({ title: 'Service Unavailable', detail: 'The database is unavailable.' }, 503);
    const { root } = await mount();
    const input = type(root, 'will fail');
    submit(root);
    await vi.waitFor(() => expect(root.querySelector('[data-testid="retry"]')).not.toBeNull());
    expect(input.value).toBe('');
    expect(root.querySelector('[data-testid="message-error"]')?.textContent).toContain('The database is unavailable.');
    const firstId = JSON.parse(String(posts()[0][1].body)).clientMessageId;

    sendResponse = () => json(message(3, 'will fail', 'CONTACT', firstId), 201);
    (root.querySelector('[data-testid="retry"]') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(root.querySelector('[data-testid="retry"]')).toBeNull());

    expect(posts()).toHaveLength(2);
    expect(JSON.parse(String(posts()[1][1].body)).clientMessageId).toBe(firstId);
    expect(bodies(root).filter((b) => b === 'will fail')).toHaveLength(1);
  });

  it('liveEventBeforeSendResponseRendersOnce', async () => {
    let respond: (r: Response) => void = () => undefined;
    sendResponse = () => new Promise<Response>((resolve) => (respond = resolve));
    const { root } = await mount();
    type(root, 'race');
    submit(root);
    await vi.waitFor(() => expect(posts()).toHaveLength(1));
    const cid = JSON.parse(String(posts()[0][1].body)).clientMessageId;
    const sent = message(3, 'race', 'CONTACT', cid);

    subscriptions[0].handlers['publication']({ data: envelope('message.created', { message: sent }, 'e3') });
    await vi.waitFor(() => expect(bodies(root)).toEqual(['earlier question', 'agent answer', 'race']));
    respond(new Response(JSON.stringify(sent), { status: 201, headers: { 'Content-Type': 'application/json' } }));
    await new Promise((r) => setTimeout(r, 20));

    expect(bodies(root)).toEqual(['earlier question', 'agent answer', 'race']);
    expect(root.querySelectorAll('[data-testid="message"][data-state="sent"]')).toHaveLength(3);
  });

  it('ignores blank input with a validation hint', async () => {
    const { root } = await mount();
    submit(root);
    await vi.waitFor(() => expect(root.querySelector('[data-testid="widget-notice"]')?.textContent).toContain('Type a message'));
    expect(posts()).toHaveLength(0);
  });

  it('typingIndicatorClearsAfterTimeout', async () => {
    const { root } = await mount();
    vi.useFakeTimers();
    subscriptions[0].handlers['publication']({
      data: envelope('typing', { participantType: 'AGENT', participantId: 'agentbob', typing: true }, 'ty1'),
    });
    await vi.advanceTimersByTimeAsync(0);
    expect(root.querySelector('[data-testid="typing-indicator"]')).not.toBeNull();
    await vi.advanceTimersByTimeAsync(5100);
    expect(root.querySelector('[data-testid="typing-indicator"]')).toBeNull();
  });

  it('archivedConversationOffersNewConversation', async () => {
    sendResponse = () => json({ title: 'Conflict', detail: 'Archived.', code: 'CONVERSATION_ARCHIVED' }, 409);
    const { root } = await mount();
    type(root, 'too late');
    submit(root);
    await vi.waitFor(() => expect(root.querySelector('[data-testid="new-conversation"]')).not.toBeNull());
    expect(root.querySelector('[data-testid="conversation-status"]')?.textContent).toContain('Closed');

    view = conversation({ id: 'c-1' });
    (root.querySelector('[data-testid="new-conversation"]') as HTMLButtonElement).click();
    await vi.waitFor(() => expect(root.querySelector('[data-testid="new-conversation"]')).toBeNull());
    const calls = (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls;
    expect(calls.filter(([u, i]) => u.endsWith('/api/v1/widget/conversations') && i?.method === 'POST')).toHaveLength(2);
  });

  it('bodiesRenderAsText', async () => {
    const evil = '<img src=x onerror="window.__pwned=1">';
    const { root } = await mount();
    subscriptions[0].handlers['publication']({ data: envelope('message.created', { message: message(3, evil, 'AGENT') }, 'e3') });
    await vi.waitFor(() => expect(bodies(root)).toContain(evil));
    expect(root.querySelector('[data-testid="widget-messages"] img')).toBeNull();
  });
});
