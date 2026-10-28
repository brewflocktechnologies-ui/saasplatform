import { ApiError, createWidgetSessionStore, ensureSession, type WidgetSession } from '../index';

const session = (token: string, contactId = 'c1'): WidgetSession => ({
  sessionToken: token,
  expiresAt: '2026-10-28T10:00:00Z',
  tenantId: 'tenant-a',
  inbox: { id: 'i1', name: 'Website' },
  contact: { id: contactId, displayName: 'Ada' },
});

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => void values.set(key, value),
    removeItem: (key: string) => void values.delete(key),
  };
}

describe('createWidgetSessionStore', () => {
  it('persists and restores a session', () => {
    const storage = memoryStorage();
    const store = createWidgetSessionStore('wk', storage);

    store.save({ sessionToken: 's', expiresAt: 'x', tenantId: 't', contactId: 'c', displayName: null });

    expect(store.load()).toMatchObject({ sessionToken: 's', contactId: 'c' });
    expect(createWidgetSessionStore('wk', storage).load()).toMatchObject({ sessionToken: 's' });
  });

  it('ignores storage failures and corrupt values', () => {
    const broken = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    };
    const store = createWidgetSessionStore('wk', broken);
    expect(() => store.save({ sessionToken: 's', expiresAt: 'x', tenantId: 't', contactId: 'c', displayName: null })).not.toThrow();
    expect(store.load()).toBeNull();

    const corrupt = memoryStorage();
    corrupt.setItem('cp.session.wk', 'not json');
    expect(createWidgetSessionStore('wk', corrupt).load()).toBeNull();
  });
});

describe('ensureSession', () => {
  it('refreshes a stored token once and keeps the same contact', async () => {
    const storage = memoryStorage();
    const store = createWidgetSessionStore('wk', storage);
    store.save({ sessionToken: 'old', expiresAt: 'x', tenantId: 't', contactId: 'c1', displayName: 'Ada' });
    const api = {
      createSession: vi.fn(async (body: { sessionToken?: string }) => session('new', 'c1')),
    };

    const result = await ensureSession(api, store, 'wk');

    expect(api.createSession).toHaveBeenCalledWith({ widgetKey: 'wk', sessionToken: 'old' });
    expect(result.sessionToken).toBe('new');
    expect(store.load()?.sessionToken).toBe('new');
  });

  it('starts a new session when the stored token is rejected', async () => {
    const store = createWidgetSessionStore('wk', memoryStorage());
    store.save({ sessionToken: 'old', expiresAt: 'x', tenantId: 't', contactId: 'c1', displayName: null });
    const api = {
      createSession: vi
        .fn()
        .mockRejectedValueOnce(new ApiError(401, 'expired', 'UNAUTHENTICATED'))
        .mockResolvedValueOnce(session('fresh', 'c2')),
    };

    const result = await ensureSession(api, store, 'wk', { externalId: 'visitor-1' });

    expect(api.createSession).toHaveBeenLastCalledWith({ widgetKey: 'wk', externalId: 'visitor-1', displayName: undefined });
    expect(result.contactId).toBe('c2');
  });

  it('propagates non-auth failures instead of starting a new session', async () => {
    const store = createWidgetSessionStore('wk', memoryStorage());
    store.save({ sessionToken: 'old', expiresAt: 'x', tenantId: 't', contactId: 'c1', displayName: null });
    const api = { createSession: vi.fn().mockRejectedValue(new ApiError(503, 'down')) };

    await expect(ensureSession(api, store, 'wk')).rejects.toMatchObject({ status: 503 });
  });
});
