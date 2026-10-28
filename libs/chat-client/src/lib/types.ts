// Mirrors libs/contracts (OpenAPI components + json-schema/chat). Keep in sync; contract tests validate the schemas.

export type ConversationStatus = 'OPEN' | 'PENDING' | 'RESOLVED';

export type AssignmentState =
  | 'NONE'
  | 'REQUESTED'
  | 'QUEUED'
  | 'ASSIGNED'
  | 'RELEASE_PENDING'
  | 'REOPEN_PENDING'
  | 'FAILED';

export type Availability = 'ONLINE' | 'AWAY' | 'OFFLINE';

/** The assignment shape of contact responses and of every conversation.* event: both keys always present (R-P29). */
export interface ContactAssignmentView {
  state: AssignmentState;
  agentId: string | null;
}

/** Agent responses only: the contact view plus the routing details (never sent to visitors, R-P3). */
export interface AssignmentView extends ContactAssignmentView {
  decisionId: string | null;
  reason: string | null;
  explanation: string | null;
}

export interface ConversationView {
  id: string;
  tenantId: string;
  inboxId: string;
  contactId: string;
  contactDisplayName: string | null;
  status: ConversationStatus;
  version: number;
  lastSequence: number;
  lastMessageAt: string | null;
  lastMessagePreview: string | null;
  assignment: ContactAssignmentView | AssignmentView;
  resolvedAt: string | null;
  archivedAt: string | null;
  purgedAt: string | null;
  createdAt: string;
  updatedAt: string;
  unreadCount: number;
  agentOnline: boolean | null;
}

export interface MessageView {
  id: string;
  conversationId: string;
  sequence: number;
  senderType: 'CONTACT' | 'AGENT' | 'BOT' | 'SYSTEM';
  senderId: string;
  messageType: 'TEXT';
  body: string;
  clientMessageId: string | null;
  createdAt: string;
  editedAt: string | null;
  deletedAt: string | null;
}

export interface MessagePage {
  items: MessageView[];
  hasMore: boolean;
}

export interface InboxView {
  id: string;
  name: string;
  widgetKey: string;
  allocationQueueId: string;
}

export interface InboxPage {
  items: ConversationView[];
  nextCursor: string | null;
}

export interface WidgetSession {
  sessionToken: string;
  expiresAt: string;
  tenantId: string;
  inbox: { id: string; name: string };
  contact: { id: string; displayName: string | null };
}

export interface AgentSession {
  sessionToken: string;
  expiresAt: string;
  tenantId: string;
  agentId: string;
  displayName: string;
}

export interface AgentMe {
  agentId: string;
  tenantId: string;
  displayName: string;
  availability: Availability;
  lastHeartbeatAt: string | null;
}

export interface RealtimeToken {
  token: string;
  expiresAt: string;
}

export interface EventEnvelope<T> {
  eventId: string;
  eventType: string;
  schemaVersion: number;
  tenantId: string;
  conversationId: string | null;
  occurredAt: string;
  data: T;
}

export type ConversationChange =
  | 'ASSIGNED'
  | 'RESOLVED'
  | 'REOPENED'
  | 'PENDING'
  | 'ARCHIVED'
  | 'UNARCHIVED'
  | 'ASSIGNMENT_QUEUED'
  | 'ASSIGNMENT_FAILED'
  | 'ASSIGNMENT_UPDATED';

export type MessageCreatedEvent = EventEnvelope<{ message: MessageView }> & { eventType: 'message.created' };

export type ConversationCreatedEvent = EventEnvelope<{ conversation: ConversationView }> & {
  eventType: 'conversation.created';
};

export type ConversationUpdatedEvent = EventEnvelope<{
  conversation: ConversationView;
  change: ConversationChange;
}> & { eventType: 'conversation.updated' };

export type TypingEvent = EventEnvelope<{
  participantType: 'CONTACT' | 'AGENT';
  participantId: string;
  typing: boolean;
}> & { eventType: 'typing' };

export type ChatEvent = MessageCreatedEvent | ConversationCreatedEvent | ConversationUpdatedEvent | TypingEvent;

export const MAX_MESSAGE_LENGTH = 4000;

export const conversationChannel = (conversationId: string) => `chat:conversation:${conversationId}`;

export const inboxChannel = (tenantId: string, inboxId: string) => `chat:inbox:${tenantId}:${inboxId}`;
