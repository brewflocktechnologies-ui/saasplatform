import type { MessageView } from './types';

/** Merge pages and live messages: dedupe by id, order by sequence, then id (D4, R2). */
export function mergeMessages(current: MessageView[], incoming: MessageView[]): MessageView[] {
  const byId = new Map<string, MessageView>();
  for (const message of [...current, ...incoming]) {
    byId.set(message.id, message);
  }
  return [...byId.values()].sort((a, b) => a.sequence - b.sequence || a.id.localeCompare(b.id));
}
