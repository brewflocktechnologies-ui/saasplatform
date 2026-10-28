import { ApiError, createAgentApi, createWidgetApi } from '../index';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('createWidgetApi', () => {
  it('sends the bearer token on protected calls and none on the session call', async () => {
    const fetchImpl = vi.fn(async () => json({ sessionToken: 's', expiresAt: 'x', tenantId: 't', inbox: { id: 'i', name: 'Website' }, contact: { id: 'c', displayName: null } }));
    const api = createWidgetApi('http://api.test', () => 'token-1', fetchImpl as unknown as typeof fetch);

    await api.createSession({ widgetKey: 'wk' });
    await api.startOrResume();

    const [sessionInit, startInit] = fetchImpl.mock.calls.map((call) => call[1] as RequestInit);
    expect((sessionInit.headers as Record<string, string>).Authorization).toBeUndefined();
    expect((startInit.headers as Record<string, string>).Authorization).toBe('Bearer token-1');
  });

  it('maps problem details to status, code and traceId', async () => {
    const fetchImpl = vi.fn(async () =>
      new Response(JSON.stringify({ status: 409, code: 'CONVERSATION_ARCHIVED', detail: 'archived', traceId: 'abc' }), {
        status: 409,
      }),
    );
    const api = createWidgetApi('http://api.test', () => 't', fetchImpl as unknown as typeof fetch);

    await expect(api.sendMessage('c1', { clientMessageId: 'k', body: 'hi' })).rejects.toEqual(
      new ApiError(409, 'archived', 'CONVERSATION_ARCHIVED', 'abc'),
    );
  });

  it('maps network failures to status 0', async () => {
    const api = createWidgetApi('http://api.test', () => 't', (() => Promise.reject(new TypeError('boom'))) as unknown as typeof fetch);
    await expect(api.getConversation('c1')).rejects.toMatchObject({ status: 0 });
  });

  it('returns undefined for a 204 read-cursor response', async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 }));
    const api = createWidgetApi('http://api.test', () => 't', fetchImpl as unknown as typeof fetch);

    await expect(api.markRead('c1', 12)).resolves.toBeUndefined();
    expect((fetchImpl.mock.calls[0][1] as RequestInit).method).toBe('PUT');
  });

  it('builds the history query from the cursor options', async () => {
    const fetchImpl = vi.fn(async () => json({ items: [], hasMore: false }));
    const api = createWidgetApi('http://api.test', () => 't', fetchImpl as unknown as typeof fetch);

    await api.listMessages('c1', { after: 100, limit: 200 });

    expect(fetchImpl.mock.calls[0][0]).toBe(
      'http://api.test/api/v1/widget/conversations/c1/messages?after=100&limit=200',
    );
  });
});

describe('createAgentApi', () => {
  it('sends the idempotency key and body on assign', async () => {
    const fetchImpl = vi.fn(async () => json({ id: 'c1' }));
    const api = createAgentApi('http://api.test', () => 'agent-token', fetchImpl as unknown as typeof fetch);

    await api.assign('c1', { mode: 'CLAIM', expectedVersion: 3 }, 'claim-1');

    const [url, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://api.test/api/v1/agent/conversations/c1/assign');
    expect((init.headers as Record<string, string>)['Idempotency-Key']).toBe('claim-1');
    expect(JSON.parse(String(init.body))).toEqual({ mode: 'CLAIM', expectedVersion: 3 });
  });

  it('sends the lifecycle command as a POST with an empty body by default', async () => {
    const fetchImpl = vi.fn(async () => json({ id: 'c1' }));
    const api = createAgentApi('http://api.test', () => 't', fetchImpl as unknown as typeof fetch);

    await api.resolve('c1');

    expect(fetchImpl.mock.calls[0][0]).toBe('http://api.test/api/v1/agent/conversations/c1/resolve');
    expect((fetchImpl.mock.calls[0][1] as RequestInit).method).toBe('POST');
  });
});
