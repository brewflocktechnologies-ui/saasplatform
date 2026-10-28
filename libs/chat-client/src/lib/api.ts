import type {
  AgentMe,
  AgentSession,
  Availability,
  ContactAssignmentView,
  ConversationView,
  InboxPage,
  InboxView,
  MessagePage,
  MessageView,
  RealtimeToken,
  RealtimeToken as Token,
  WidgetSession,
} from './types';

/** HTTP failure with the server's problem `code`/`traceId`. status 0 = unreachable (network or timeout). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
    readonly traceId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

type FetchImpl = typeof fetch;

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT';
  body?: unknown;
  headers?: Record<string, string>;
  auth?: boolean;
  fetchImpl?: FetchImpl;
}

function createRequest(baseUrl: string, tokenProvider: () => string | null) {
  return async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const fetchImpl = options.fetchImpl ?? ((...args) => fetch(...args));
    const token = options.auth === false ? null : tokenProvider();
    const headers: Record<string, string> = { ...options.headers };
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    let res: Response;
    try {
      res = await fetchImpl(`${baseUrl}${path}`, {
        method: options.method ?? 'GET',
        cache: 'no-store',
        signal: AbortSignal.timeout(8000),
        headers: Object.keys(headers).length > 0 ? headers : undefined,
        body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      });
    }
    catch {
      throw new ApiError(0, `Cannot reach the API at ${baseUrl}`);
    }
    if (!res.ok) {
      let detail = `HTTP ${res.status}`;
      let code: string | undefined;
      let traceId: string | undefined;
      try {
        const problem = (await res.json()) as { detail?: string; title?: string; code?: string; traceId?: string };
        detail = problem.detail ?? problem.title ?? detail;
        code = problem.code;
        traceId = problem.traceId;
      }
      catch {
        // non-JSON error body: keep the status text
      }
      throw new ApiError(res.status, detail, code, traceId);
    }
    if (res.status === 204) {
      return undefined as T;
    }
    return (await res.json()) as T;
  };
}

/** Visitor API: session start/refresh plus the contact-scoped conversation endpoints. */
export function createWidgetApi(
  baseUrl: string,
  tokenProvider: () => string | null,
  fetchImpl: FetchImpl = (...args) => fetch(...args),
) {
  const request = createRequest(baseUrl, tokenProvider);
  const q = encodeURIComponent;
  const fetchOption = { fetchImpl };
  return {
    createSession: (body: { widgetKey: string; externalId?: string; displayName?: string; sessionToken?: string }) =>
      request<WidgetSession>('/api/v1/widget/sessions', { method: 'POST', body, auth: false, ...fetchOption }),
    startOrResume: () => request<ConversationView>('/api/v1/widget/conversations', { method: 'POST', ...fetchOption }),
    getConversation: (id: string) =>
      request<ConversationView>(`/api/v1/widget/conversations/${q(id)}`, { ...fetchOption }),
    listMessages: (id: string, options: { before?: number; after?: number; limit?: number } = {}) => {
      const params = new URLSearchParams();
      if (options.before !== undefined) params.set('before', String(options.before));
      if (options.after !== undefined) params.set('after', String(options.after));
      if (options.limit !== undefined) params.set('limit', String(options.limit));
      const query = params.size > 0 ? `?${params}` : '';
      return request<MessagePage>(`/api/v1/widget/conversations/${q(id)}/messages${query}`, { ...fetchOption });
    },
    sendMessage: (id: string, body: { clientMessageId: string; body: string }) =>
      request<MessageView>(`/api/v1/widget/conversations/${q(id)}/messages`, {
        method: 'POST',
        body,
        ...fetchOption,
      }),
    typing: (id: string, typing: boolean) =>
      request<void>(`/api/v1/widget/conversations/${q(id)}/typing`, {
        method: 'POST',
        body: { typing },
        ...fetchOption,
      }),
    markRead: (id: string, lastReadSequence: number) =>
      request<void>(`/api/v1/widget/conversations/${q(id)}/read`, {
        method: 'PUT',
        body: { lastReadSequence },
        ...fetchOption,
      }),
    connectionToken: () => request<Token>('/api/v1/widget/realtime/connection-token', { ...fetchOption }),
    subscriptionToken: (channel: string) =>
      request<Token>(`/api/v1/widget/realtime/subscription-token?channel=${q(channel)}`, { ...fetchOption }),
  };
}

export type WidgetApi = ReturnType<typeof createWidgetApi>;

/** Agent API: local login, presence, inbox lists, conversation reads and lifecycle commands. */
export function createAgentApi(
  baseUrl: string,
  tokenProvider: () => string | null,
  fetchImpl: FetchImpl = (...args) => fetch(...args),
) {
  const request = createRequest(baseUrl, tokenProvider);
  const q = encodeURIComponent;
  const fetchOption = { fetchImpl };
  const command = (id: string, action: string, body?: Record<string, unknown>) =>
    request<ConversationView>(`/api/v1/agent/conversations/${q(id)}/${action}`, {
      method: 'POST',
      body: body ?? {},
      ...fetchOption,
    });
  return {
    localLogin: (agentId: string, tenantId?: string) =>
      request<AgentSession>('/api/v1/agent/local-login', {
        method: 'POST',
        body: { agentId, tenantId },
        auth: false,
        ...fetchOption,
      }),
    me: () => request<AgentMe>('/api/v1/agent/me', { ...fetchOption }),
    setPresence: (availability: Availability) =>
      request<AgentMe>('/api/v1/agent/presence', { method: 'PUT', body: { availability }, ...fetchOption }),
    inboxes: () => request<InboxView[]>('/api/v1/agent/inboxes', { ...fetchOption }),
    listConversations: (
      inboxId: string,
      options: { view?: 'queue' | 'mine' | 'all'; archived?: boolean; cursor?: string; limit?: number } = {},
    ) => {
      const params = new URLSearchParams();
      if (options.view) params.set('view', options.view);
      if (options.archived !== undefined) params.set('archived', String(options.archived));
      if (options.cursor) params.set('cursor', options.cursor);
      if (options.limit !== undefined) params.set('limit', String(options.limit));
      const query = params.size > 0 ? `?${params}` : '';
      return request<InboxPage>(`/api/v1/agent/inboxes/${q(inboxId)}/conversations${query}`, { ...fetchOption });
    },
    getConversation: (id: string) =>
      request<ConversationView>(`/api/v1/agent/conversations/${q(id)}`, { ...fetchOption }),
    listMessages: (id: string, options: { before?: number; after?: number; limit?: number } = {}) => {
      const params = new URLSearchParams();
      if (options.before !== undefined) params.set('before', String(options.before));
      if (options.after !== undefined) params.set('after', String(options.after));
      if (options.limit !== undefined) params.set('limit', String(options.limit));
      const query = params.size > 0 ? `?${params}` : '';
      return request<MessagePage>(`/api/v1/agent/conversations/${q(id)}/messages${query}`, { ...fetchOption });
    },
    sendMessage: (id: string, body: { clientMessageId: string; body: string }) =>
      request<MessageView>(`/api/v1/agent/conversations/${q(id)}/messages`, { method: 'POST', body, ...fetchOption }),
    typing: (id: string, typing: boolean) =>
      request<void>(`/api/v1/agent/conversations/${q(id)}/typing`, {
        method: 'POST',
        body: { typing },
        ...fetchOption,
      }),
    markRead: (id: string, lastReadSequence: number) =>
      request<void>(`/api/v1/agent/conversations/${q(id)}/read`, {
        method: 'PUT',
        body: { lastReadSequence },
        ...fetchOption,
      }),
    assign: (
      id: string,
      body: { mode: 'AUTO' | 'CLAIM' | 'REASSIGN'; expectedVersion?: number },
      idempotencyKey: string,
    ) =>
      request<ConversationView>(`/api/v1/agent/conversations/${q(id)}/assign`, {
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
        ...fetchOption,
      }),
    resolve: (id: string, body?: { expectedVersion?: number; reason?: string }) => command(id, 'resolve', body),
    reopen: (id: string, body?: { expectedVersion?: number; reason?: string }) => command(id, 'reopen', body),
    pending: (id: string, body?: { expectedVersion?: number; reason?: string }) => command(id, 'pending', body),
    archive: (id: string, body?: { expectedVersion?: number; reason?: string }) => command(id, 'archive', body),
    unarchive: (id: string, body?: { expectedVersion?: number; reason?: string }) => command(id, 'unarchive', body),
    connectionToken: () => request<Token>('/api/v1/agent/realtime/connection-token', { ...fetchOption }),
    subscriptionToken: (channel: string) =>
      request<Token>(`/api/v1/agent/realtime/subscription-token?channel=${q(channel)}`, { ...fetchOption }),
  };
}

export type AgentApi = ReturnType<typeof createAgentApi>;

/** Kept for the dashboard's assignment badge typing. */
export type { ContactAssignmentView, RealtimeToken };
