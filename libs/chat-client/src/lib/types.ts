// Mirrors libs/contracts (OpenAPI components + json-schema/chat). Keep in sync; tests validate against the schemas.

/** LOCAL POC — NOT REAL AUTHENTICATION: the only two demo identities. */
export type DemoActorId = 'userone' | 'agentbob';

export interface Conversation {
  id: string;
  visitorId: string;
  assignedAgentId: string;
  status: 'OPEN' | 'CLOSED';
  createdAt: string;
  updatedAt: string;
  lastMessageAt?: string | null;
  lastMessagePreview?: string | null;
}

export interface ChatMessage {
  id: string;
  conversationId: string;
  senderId: string;
  senderRole: 'VISITOR' | 'AGENT';
  body: string;
  createdAt: string;
}

export interface SendMessageResponse {
  message: ChatMessage;
  /** FAILED = persisted, but the live event was not published. Do not resend. */
  realtimeDelivery: 'PUBLISHED' | 'FAILED';
}

export interface RealtimeToken {
  token: string;
  expiresAt: string;
}

export interface MessageCreatedEvent {
  type: 'message.created';
  conversationId: string;
  message: ChatMessage;
}

export interface ConversationCreatedEvent {
  type: 'conversation.created';
  conversation: Conversation;
}

export type ChatEvent = MessageCreatedEvent | ConversationCreatedEvent;

export const INBOX_CHANNEL = 'chat:inbox';

export const conversationChannel = (conversationId: string) => `chat:conversation:${conversationId}`;

export const MAX_MESSAGE_LENGTH = 4000;
