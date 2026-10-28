import { ApiError } from './api';
import type { MessageView } from './types';

export type PendingState = 'pending' | 'sent' | 'failed';

export interface PendingMessage {
  clientMessageId: string;
  body: string;
  state: PendingState;
  error?: string;
  messageId?: string;
  sequence?: number;
}

/**
 * The pending-message queue: one stable `clientMessageId` per attempt so a retry replays instead of creating a second
 * row (D5, master spec 14.3). A successful send hands the persisted `MessageView` to the caller and marks the bubble sent.
 */
export function createPendingQueue(options: {
  send: (clientMessageId: string, body: string) => Promise<MessageView>;
  newId?: () => string;
}) {
  const newId = options.newId ?? (() => crypto.randomUUID());
  const items = new Map<string, PendingMessage>();
  const listeners = new Set<(messages: PendingMessage[]) => void>();

  const snapshot = (): PendingMessage[] => [...items.values()].map((item) => ({ ...item }));

  function notify(): void {
    const messages = snapshot();
    for (const listener of listeners) {
      listener(messages);
    }
  }

  async function deliver(item: PendingMessage): Promise<void> {
    item.state = 'pending';
    item.error = undefined;
    notify();
    try {
      const message = await options.send(item.clientMessageId, item.body);
      item.state = 'sent';
      item.messageId = message.id;
      item.sequence = message.sequence;
    }
    catch (error) {
      item.state = 'failed';
      item.error = error instanceof ApiError ? error.message : 'unexpected error';
    }
    notify();
  }

  return {
    enqueue(body: string): PendingMessage {
      const item: PendingMessage = { clientMessageId: newId(), body, state: 'pending' };
      items.set(item.clientMessageId, item);
      void deliver(item);
      return { ...item };
    },
    /** Retries a failed bubble with the same `clientMessageId` (the server replays the original if it did commit). */
    retry(clientMessageId: string): void {
      const item = items.get(clientMessageId);
      if (item && item.state === 'failed') {
        void deliver(item);
      }
    },
    /** Marks a bubble as sent when its own `message.created` event arrives before the POST response. */
    markSent(clientMessageId: string, message: MessageView): void {
      const item = items.get(clientMessageId);
      if (item) {
        item.state = 'sent';
        item.messageId = message.id;
        item.sequence = message.sequence;
        notify();
      }
    },
    list: snapshot,
    subscribe(listener: (messages: PendingMessage[]) => void): () => void {
      listeners.add(listener);
      listener(snapshot());
      return () => listeners.delete(listener);
    },
  };
}

export type PendingQueue = ReturnType<typeof createPendingQueue>;
