import type { ChatMessage } from './types';

/**
 * Merge messages by id (HTTP response + WebSocket event + history reload never duplicate) and keep the server's
 * order: createdAt, then id — the same ordering as the history endpoint.
 */
export function mergeMessages(existing: readonly ChatMessage[], incoming: readonly ChatMessage[]): ChatMessage[] {
  const byId = new Map(existing.map((m) => [m.id, m]));
  for (const m of incoming) byId.set(m.id, m);
  return [...byId.values()].sort((a, b) =>
    a.createdAt === b.createdAt ? (a.id < b.id ? -1 : a.id > b.id ? 1 : 0) : Date.parse(a.createdAt) - Date.parse(b.createdAt),
  );
}
