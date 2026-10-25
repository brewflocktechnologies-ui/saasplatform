import type {
  ChatMessage,
  Conversation,
  DemoActorId,
  RealtimeToken,
  SendMessageResponse,
} from './types';

/** HTTP failure with the server's problem-details `detail` (or a network message). status 0 = unreachable. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export type ChatApi = ReturnType<typeof createChatApi>;

/** Thin typed client for /api/v1/demo/* (libs/contracts/openapi/platform-api.yaml). */
export function createChatApi(baseUrl: string, fetchImpl: typeof fetch = (...args) => fetch(...args)) {
  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    let res: Response;
    try {
      res = await fetchImpl(`${baseUrl}${path}`, {
        cache: 'no-store',
        signal: AbortSignal.timeout(8000),
        ...init,
        headers: init?.body ? { 'Content-Type': 'application/json' } : undefined,
      });
    } catch {
      throw new ApiError(0, `Cannot reach the API at ${baseUrl}`);
    }
    if (!res.ok) {
      let detail = `HTTP ${res.status}`;
      try {
        const problem = (await res.json()) as { detail?: string; title?: string };
        detail = problem.detail ?? problem.title ?? detail;
      } catch {
        // non-JSON error body: keep the status text
      }
      throw new ApiError(res.status, detail);
    }
    return (await res.json()) as T;
  }

  const q = encodeURIComponent;
  return {
    ensureConversation: () => request<Conversation>('/api/v1/demo/conversations', { method: 'POST' }),
    listConversations: (actorId: DemoActorId) =>
      request<Conversation[]>(`/api/v1/demo/conversations?actorId=${q(actorId)}`),
    listMessages: (conversationId: string, actorId: DemoActorId) =>
      request<ChatMessage[]>(`/api/v1/demo/conversations/${q(conversationId)}/messages?actorId=${q(actorId)}`),
    sendMessage: (conversationId: string, actorId: DemoActorId, text: string) =>
      request<SendMessageResponse>(`/api/v1/demo/conversations/${q(conversationId)}/messages`, {
        method: 'POST',
        body: JSON.stringify({ actorId, text }),
      }),
    connectionToken: (actorId: DemoActorId) =>
      request<RealtimeToken>(`/api/v1/demo/realtime/connection-token?actorId=${q(actorId)}`),
    subscriptionToken: (actorId: DemoActorId, channel: string) =>
      request<RealtimeToken>(
        `/api/v1/demo/realtime/subscription-token?actorId=${q(actorId)}&channel=${q(channel)}`,
      ),
  };
}
