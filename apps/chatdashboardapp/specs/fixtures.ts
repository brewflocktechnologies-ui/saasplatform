import type { ChatEvent, ConversationView, MessageView } from '@chat-platform/chat-client';

export const conversation = (overrides: Partial<ConversationView> = {}): ConversationView => ({
  id: 'c1',
  tenantId: 't1',
  inboxId: 'i1',
  contactId: 'k1',
  contactDisplayName: 'User One',
  status: 'OPEN',
  version: 1,
  lastSequence: 0,
  lastMessageAt: null,
  lastMessagePreview: null,
  assignment: { state: 'ASSIGNED', agentId: 'agentbob' },
  resolvedAt: null,
  archivedAt: null,
  purgedAt: null,
  createdAt: '2026-10-27T10:00:00Z',
  updatedAt: '2026-10-27T10:00:00Z',
  unreadCount: 0,
  agentOnline: null,
  ...overrides,
});

export const message = (
  sequence: number,
  body = `message ${sequence}`,
  senderType: MessageView['senderType'] = 'CONTACT',
  conversationId = 'c1',
): MessageView => ({
  id: `m${conversationId}-${sequence}`,
  conversationId,
  sequence,
  senderType,
  senderId: senderType === 'CONTACT' ? 'k1' : 'agentbob',
  messageType: 'TEXT',
  body,
  clientMessageId: null,
  createdAt: `2026-10-27T10:00:${String(sequence).padStart(2, '0')}Z`,
  editedAt: null,
  deletedAt: null,
});

export const event = (eventType: string, data: unknown, eventId: string, conversationId = 'c1'): ChatEvent =>
  ({ eventId, eventType, schemaVersion: 1, tenantId: 't1', conversationId, occurredAt: '2026-10-27T10:01:00Z', data }) as ChatEvent;
