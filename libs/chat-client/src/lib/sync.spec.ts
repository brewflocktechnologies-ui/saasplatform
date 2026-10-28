import { createConversationSync, type ChatEvent, type ConversationView, type MessagePage, type MessageView } from '../index';

const conversation = (version: number, patch: Partial<ConversationView> = {}): ConversationView => ({
  id: 'c1',
  tenantId: 'tenant-a',
  inboxId: 'i1',
  contactId: 'k1',
  contactDisplayName: null,
  status: 'OPEN',
  version,
  lastSequence: 0,
  lastMessageAt: null,
  lastMessagePreview: null,
  assignment: { state: 'REQUESTED', agentId: null },
  resolvedAt: null,
  archivedAt: null,
  purgedAt: null,
  createdAt: '2026-10-27T10:00:00Z',
  updatedAt: '2026-10-27T10:00:00Z',
  unreadCount: 0,
  agentOnline: null,
  ...patch,
});

const message = (sequence: number): MessageView => ({
  id: `m-${sequence}`,
  conversationId: 'c1',
  sequence,
  senderType: 'CONTACT',
  senderId: 'k1',
  messageType: 'TEXT',
  body: `body ${sequence}`,
  clientMessageId: null,
  createdAt: `2026-10-27T10:00:0${sequence}Z`,
  editedAt: null,
  deletedAt: null,
});

const messageEvent = (sequence: number, eventId = `e-${sequence}`): ChatEvent =>
  ({
    eventId,
    eventType: 'message.created',
    schemaVersion: 1,
    tenantId: 'tenant-a',
    conversationId: 'c1',
    occurredAt: '2026-10-27T10:00:00Z',
    data: { message: message(sequence) },
  }) as ChatEvent;

const conversationEvent = (
  version: number,
  change = 'ASSIGNMENT_UPDATED',
  eventId = `cv-${version}`,
): ChatEvent =>
  ({
    eventId,
    eventType: 'conversation.updated',
    schemaVersion: 1,
    tenantId: 'tenant-a',
    conversationId: 'c1',
    occurredAt: '2026-10-27T10:00:00Z',
    data: { conversation: conversation(version), change },
  }) as ChatEvent;

const page = (items: MessageView[], hasMore = false): MessagePage => ({ items, hasMore });

function setup(options: {
  latest?: MessagePage;
  after?: (after: number) => Promise<MessagePage>;
  view?: ConversationView;
}) {
  const onMessages = vi.fn();
  const onConversationUpdated = vi.fn();
  const fetchLatest = vi.fn(async () => options.latest ?? page([]));
  const fetchAfter = vi.fn(options.after ?? (async () => page([])));
  const fetchConversation = vi.fn(async () => options.view ?? conversation(1));
  const sync = createConversationSync({
    conversationId: 'c1',
    fetchLatest,
    fetchAfter,
    fetchConversation,
    onMessages,
    onConversationUpdated,
  });
  return { sync, onMessages, onConversationUpdated, fetchLatest, fetchAfter, fetchConversation };
}

describe('createConversationSync', () => {
  it('applies events in order, ignoring duplicate event ids and stale sequences', async () => {
    const { sync, onMessages } = setup({ latest: page([message(1), message(2)]) });
    await sync.handleSubscribed({ wasRecovering: false, recovered: false });
    expect(onMessages).toHaveBeenLastCalledWith([message(1), message(2)]);

    sync.handleEvent(messageEvent(3));
    expect(sync.lastSequence()).toBe(3);
    sync.handleEvent(messageEvent(3));
    sync.handleEvent(messageEvent(2));
    expect(onMessages).toHaveBeenCalledTimes(2);
  });

  it('catches up from the last sequence when a gap appears', async () => {
    let missed = false;
    const { sync, fetchAfter } = setup({
      latest: page([message(1)]),
      after: async () => (missed ? page([message(2), message(3)]) : page([])),
    });
    await sync.handleSubscribed({ wasRecovering: false, recovered: false });
    fetchAfter.mockClear();
    missed = true;

    await sync.handleEvent(messageEvent(3));

    expect(fetchAfter).toHaveBeenCalledWith(1);
    expect(sync.lastSequence()).toBe(3);
  });

  it('applies the message exactly once when an event arrives during the initial load', async () => {
    let resolveLatest: ((value: MessagePage) => void) | undefined;
    const onMessages = vi.fn();
    const sync = createConversationSync({
      conversationId: 'c1',
      fetchLatest: () => new Promise<MessagePage>((resolve) => (resolveLatest = resolve)),
      fetchAfter: async () => page([]),
      fetchConversation: async () => conversation(1),
      onMessages,
    });

    const subscribed = sync.handleSubscribed({ wasRecovering: false, recovered: false });
    sync.handleEvent(messageEvent(1));
    resolveLatest?.(page([message(1)]));
    await subscribed;

    expect(onMessages).toHaveBeenLastCalledWith([message(1)]);
  });

  it('reconciles on a failed recovery, from the last known sequence', async () => {
    const { sync, fetchAfter } = setup({ latest: page([message(5)]) });
    await sync.handleSubscribed({ wasRecovering: false, recovered: false });
    fetchAfter.mockClear();

    await sync.handleSubscribed({ wasRecovering: true, recovered: false });

    expect(fetchAfter).toHaveBeenCalledWith(5);
  });

  it('stops after five catch-up pages and takes the newest page instead', async () => {
    const latest = page([message(100)]);
    let outage = false;
    const { sync, fetchLatest, fetchAfter } = setup({
      latest,
      after: async () => (outage ? page([message(99)], true) : page([])),
    });
    await sync.handleSubscribed({ wasRecovering: false, recovered: false });
    outage = true;
    fetchLatest.mockClear();
    fetchAfter.mockClear();

    await sync.handleSubscribed({ wasRecovering: true, recovered: false });

    expect(fetchAfter).toHaveBeenCalledTimes(5);
    expect(fetchLatest).toHaveBeenCalledTimes(1);
    expect(sync.lastSequence()).toBe(100);
  });

  it('loads the newest page first on an empty start instead of paging from zero', async () => {
    const { sync, fetchAfter } = setup({ latest: page([message(9), message(10)]) });

    await sync.handleSubscribed({ wasRecovering: false, recovered: false });

    expect(fetchAfter).toHaveBeenCalledWith(10);
  });

  it('refetches the conversation on a version jump and applies a consecutive version from the payload', async () => {
    const { sync, fetchConversation, onConversationUpdated } = setup({ view: conversation(3) });
    await sync.handleSubscribed({ wasRecovering: false, recovered: false });
    expect(sync.knownVersion()).toBe(3);
    fetchConversation.mockClear();

    sync.handleEvent(conversationEvent(4));
    expect(fetchConversation).not.toHaveBeenCalled();
    expect(onConversationUpdated).toHaveBeenLastCalledWith(expect.objectContaining({ version: 4 }));

    await sync.handleEvent(conversationEvent(6));
    expect(fetchConversation).toHaveBeenCalledTimes(1);

    fetchConversation.mockClear();
    sync.handleEvent(conversationEvent(4));
    expect(fetchConversation).not.toHaveBeenCalled();
  });

  it('ignores unknown event types and tolerates extra fields', async () => {
    const { sync } = setup({ latest: page([message(1)]) });
    await sync.handleSubscribed({ wasRecovering: false, recovered: false });

    expect(() => sync.handleEvent({ eventId: 'x', eventType: 'typing.later' } as unknown as ChatEvent)).not.toThrow();
    expect(() =>
      sync.handleEvent({ ...messageEvent(2), extraField: true } as unknown as ChatEvent),
    ).not.toThrow();
    expect(sync.lastSequence()).toBe(2);
  });
});
