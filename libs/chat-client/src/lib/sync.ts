import type { ChatEvent, ConversationView, MessagePage, MessageView } from './types';

export interface ConversationSyncOptions {
  conversationId: string;
  /** The newest page; used for the initial load and when the five-page catch-up bound is exceeded. */
  fetchLatest: () => Promise<MessagePage>;
  /** Catch-up page: messages newer than the given sequence (limit 200 by the caller). */
  fetchAfter: (afterSequence: number) => Promise<MessagePage>;
  fetchConversation: () => Promise<ConversationView>;
  onMessages: (messages: MessageView[]) => void;
  onConversationUpdated?: (view: ConversationView) => void;
}

export interface ConversationSync {
  handleSubscribed(context: { wasRecovering: boolean; recovered: boolean }): Promise<void>;
  handleEvent(event: ChatEvent): Promise<void> | void;
  reconcile(): Promise<void>;
  lastSequence(): number;
  knownVersion(): number;
}

/** At most five `?after=` pages, then the full reload path (Plan 01 rulings R5, R-P5). */
const MAX_CATCH_UP_PAGES = 5;

/**
 * The client sync algorithm (realtime-and-recovery.md): every `subscribed` reconciles from the API, events are deduped by
 * `eventId` (bounded set of 500 per view) and by sequence, a sequence gap triggers catch-up, and conversation events
 * follow the version rule — apply at `known + 1` while caught up, refetch otherwise.
 */
export function createConversationSync(options: ConversationSyncOptions): ConversationSync {
  const messages = new Map<number, MessageView>();
  const seenEvents = new Set<string>();
  const seenOrder: string[] = [];
  let lastSequence = 0;
  let knownVersion = -1;
  let caughtUp = false;
  let inFlight: Promise<void> | null = null;

  function trackEvent(eventId: string): boolean {
    if (seenEvents.has(eventId)) {
      return false;
    }
    seenEvents.add(eventId);
    seenOrder.push(eventId);
    if (seenOrder.length > 500) {
      const oldest = seenOrder.shift();
      if (oldest) {
        seenEvents.delete(oldest);
      }
    }
    return true;
  }

  function emit(): void {
    options.onMessages([...messages.values()].sort((a, b) => a.sequence - b.sequence || a.id.localeCompare(b.id)));
  }

  function applyMessages(items: MessageView[]): void {
    let changed = false;
    for (const message of items) {
      if (message.sequence > lastSequence) {
        lastSequence = message.sequence;
      }
      if (!messages.has(message.sequence)) {
        messages.set(message.sequence, message);
        changed = true;
      }
    }
    if (changed) {
      emit();
    }
  }

  async function refetchConversation(): Promise<void> {
    const view = await options.fetchConversation();
    if (view.version >= knownVersion) {
      knownVersion = view.version;
      options.onConversationUpdated?.(view);
    }
  }

  function applyConversation(view: ConversationView): Promise<void> | void {
    if (view.version <= knownVersion) {
      return;
    }
    if (view.version === knownVersion + 1 && caughtUp) {
      knownVersion = view.version;
      options.onConversationUpdated?.(view);
      return;
    }
    return refetchConversation();
  }

  async function reconcile(): Promise<void> {
    if (inFlight) {
      return inFlight;
    }
    inFlight = (async () => {
      caughtUp = false;
      try {
        if (lastSequence === 0) {
          applyMessages((await options.fetchLatest()).items);
        }
        let pages = 0;
        while (pages < MAX_CATCH_UP_PAGES) {
          const page = await options.fetchAfter(lastSequence);
          applyMessages(page.items);
          pages += 1;
          if (!page.hasMore) {
            break;
          }
          if (pages === MAX_CATCH_UP_PAGES) {
            // Bound exceeded: take the newest page instead of paging forward for ever.
            messages.clear();
            lastSequence = 0;
            applyMessages((await options.fetchLatest()).items);
          }
        }
        const view = await options.fetchConversation();
        knownVersion = view.version;
        options.onConversationUpdated?.(view);
        caughtUp = true;
      }
      finally {
        inFlight = null;
      }
    })();
    return inFlight;
  }

  return {
    handleSubscribed: async () => {
      await reconcile();
    },
    handleEvent: (event: ChatEvent) => {
      if (!trackEvent(event.eventId)) {
        return;
      }
      switch (event.eventType) {
        case 'message.created': {
          const message = event.data.message;
          if (message.sequence <= lastSequence) {
            return;
          }
          if (message.sequence > lastSequence + 1) {
            return reconcile();
          }
          applyMessages([message]);
          return;
        }
        case 'conversation.created':
        case 'conversation.updated': {
          return applyConversation(event.data.conversation);
        }
        default:
          // typing and unknown event types never touch history (R15)
          return;
      }
    },
    reconcile,
    lastSequence: () => lastSequence,
    knownVersion: () => knownVersion,
  };
}
