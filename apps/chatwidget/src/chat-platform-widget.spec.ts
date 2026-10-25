import { WIDGET_TAG } from './embed/v1';

// The real SDK opens a WebSocket; replace it with an inert fake that records subscriptions.
const subscriptions: string[] = [];
vi.mock('centrifuge', () => ({
  Centrifuge: class {
    on() {
      return this;
    }
    connect = () => undefined;
    disconnect = () => undefined;
    removeSubscription = () => undefined;
    newSubscription(channel: string) {
      subscriptions.push(channel);
      return { on: () => undefined, subscribe: () => undefined, unsubscribe: () => undefined };
    }
  },
}));

const CONVERSATION = { id: 'c-1', visitorId: 'userone', assignedAgentId: 'agentbob', status: 'OPEN', createdAt: '2026-10-25T10:00:00Z', updatedAt: '2026-10-25T10:00:00Z' };
const message = (id: string, body: string, role = 'VISITOR') => ({
  id,
  conversationId: 'c-1',
  senderId: role === 'VISITOR' ? 'userone' : 'agentbob',
  senderRole: role,
  body,
  createdAt: `2026-10-25T10:00:0${id.slice(-1)}Z`,
});
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));

async function mount() {
  const el = document.createElement('chat-platform-widget');
  document.body.append(el);
  await vi.waitFor(() => expect(el.shadowRoot?.querySelectorAll('[data-testid="message"]').length).toBeGreaterThan(0));
  return { el, root: el.shadowRoot as ShadowRoot };
}

describe('<chat-platform-widget>', () => {
  let sendResponse: () => Promise<Response>;

  beforeEach(() => {
    subscriptions.length = 0;
    sendResponse = () => json({ message: message('m3', 'Hello from the test'), realtimeDelivery: 'PUBLISHED' }, 201);
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (url.endsWith('/api/v1/demo/conversations') && init?.method === 'POST') return json(CONVERSATION);
        if (url.includes('/messages') && init?.method === 'POST') return sendResponse();
        if (url.includes('/messages')) return json([message('m1', 'earlier question'), message('m2', 'agent answer', 'AGENT')]);
        return json({ token: 't', expiresAt: '2026-10-25T10:05:00Z' });
      }),
    );
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.unstubAllGlobals();
  });

  it('is registered by the v1 embed entry point', () => {
    expect(customElements.get(WIDGET_TAG)).toBeDefined();
  });

  it('loads persisted history for userone and subscribes to its conversation channel', async () => {
    const { root } = await mount();
    expect(root.querySelector('[data-testid="visitor-id"]')?.textContent).toContain('userone');
    const bodies = [...root.querySelectorAll('[data-testid="message-body"]')].map((m) => m.textContent);
    expect(bodies).toEqual(['earlier question', 'agent answer']);
    expect(subscriptions).toEqual(['chat:conversation:c-1']);
  });

  it('renders the saved message from the send response and clears the composer', async () => {
    const { el, root } = await mount();
    const input = root.querySelector('input') as HTMLInputElement;
    input.value = '  Hello from the test  ';
    input.dispatchEvent(new Event('input'));
    root.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await vi.waitFor(() => expect(root.querySelectorAll('[data-testid="message"]').length).toBe(3));
    await el.updateComplete;
    expect(input.value).toBe('');
    const sent = (fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls.find(
      ([, init]) => init?.method === 'POST' && String(init.body).includes('Hello'),
    );
    expect(JSON.parse(String(sent?.[1].body))).toEqual({ actorId: 'userone', text: 'Hello from the test' });
  });

  it('ignores blank input with a validation hint and shows a not-sent error on API failure', async () => {
    const { root } = await mount();
    root.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await vi.waitFor(() => expect(root.querySelector('[data-testid="widget-notice"]')?.textContent).toContain('Type a message'));

    sendResponse = () => json({ title: 'Service Unavailable', status: 503, detail: 'The database is unavailable.' }, 503);
    const input = root.querySelector('input') as HTMLInputElement;
    input.value = 'will fail';
    input.dispatchEvent(new Event('input'));
    root.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await vi.waitFor(() =>
      expect(root.querySelector('[data-testid="widget-error"]')?.textContent).toContain('Message not sent: The database is unavailable.'),
    );
    expect(input.value).toBe('will fail');
  });

  it('keeps the message and warns when it was saved but not delivered live', async () => {
    sendResponse = () => json({ message: message('m4', 'saved only'), realtimeDelivery: 'FAILED' }, 201);
    const { root } = await mount();
    const input = root.querySelector('input') as HTMLInputElement;
    input.value = 'saved only';
    input.dispatchEvent(new Event('input'));
    root.querySelector('form')?.dispatchEvent(new Event('submit', { cancelable: true }));
    await vi.waitFor(() => expect(root.querySelector('[data-testid="widget-notice"]')?.textContent).toContain('saved'));
    expect([...root.querySelectorAll('[data-testid="message-body"]')].map((m) => m.textContent)).toContain('saved only');
  });
});
