import { ApiError } from './api';
import type { WidgetSession } from './types';

export interface StoredWidgetSession {
  sessionToken: string;
  expiresAt: string;
  tenantId: string;
  contactId: string;
  displayName: string | null;
}

export interface WidgetSessionStore {
  load(): StoredWidgetSession | null;
  save(session: StoredWidgetSession): void;
  clear(): void;
}

/** Session storage of the widget: `localStorage` under `cp.session.<widgetKey>`; every access is failure-tolerant. */
export function createWidgetSessionStore(
  widgetKey: string,
  storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | undefined = safeLocalStorage(),
): WidgetSessionStore {
  const key = `cp.session.${widgetKey}`;
  return {
    load(): StoredWidgetSession | null {
      try {
        const raw = storage?.getItem(key);
        return raw ? (JSON.parse(raw) as StoredWidgetSession) : null;
      }
      catch {
        return null;
      }
    },
    save(session: StoredWidgetSession): void {
      try {
        storage?.setItem(key, JSON.stringify(session));
      }
      catch {
        // storage disabled or full: the session simply does not survive a reload
      }
    },
    clear(): void {
      try {
        storage?.removeItem(key);
      }
      catch {
        // ignore
      }
    },
  };
}

function safeLocalStorage(): Storage | undefined {
  try {
    return typeof localStorage === 'undefined' ? undefined : localStorage;
  }
  catch {
    return undefined;
  }
}

/**
 * Ensures a contact session: a stored token is first used as a refresh token; 401/404 clears it and starts a new session
 * (a refresh beyond the 30-day window is a 401 by design).
 */
export async function ensureSession(
  api: {
    createSession(body: {
      widgetKey: string;
      externalId?: string;
      displayName?: string;
      sessionToken?: string;
    }): Promise<WidgetSession>;
  },
  store: WidgetSessionStore,
  widgetKey: string,
  identity: { externalId?: string; displayName?: string } = {},
): Promise<StoredWidgetSession> {
  const stored = store.load();
  if (stored) {
    try {
      const refreshed = await api.createSession({ widgetKey, sessionToken: stored.sessionToken });
      const session = toStored(refreshed);
      store.save(session);
      return session;
    }
    catch (error) {
      if (!(error instanceof ApiError) || (error.status !== 401 && error.status !== 404)) {
        throw error;
      }
      store.clear();
    }
  }
  const session = toStored(
    await api.createSession({
      widgetKey,
      externalId: identity.externalId,
      displayName: identity.displayName,
    }),
  );
  store.save(session);
  return session;
}

function toStored(session: WidgetSession): StoredWidgetSession {
  return {
    sessionToken: session.sessionToken,
    expiresAt: session.expiresAt,
    tenantId: session.tenantId,
    contactId: session.contact.id,
    displayName: session.contact.displayName,
  };
}
