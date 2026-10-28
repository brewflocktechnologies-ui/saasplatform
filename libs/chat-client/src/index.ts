// Framework-agnostic conversation-platform client (no React, no Lit): shared by the widget and the dashboard.
export * from './lib/types';
export { ApiError, createAgentApi, createWidgetApi, type AgentApi, type WidgetApi } from './lib/api';
export { mergeMessages } from './lib/messages';
export {
  CONNECTION_LABEL,
  createRealtime,
  type ConnectionState,
  type Realtime,
  type SubscribedContext,
  type SubscribeHandlers,
} from './lib/realtime';
export { createConversationSync, type ConversationSync, type ConversationSyncOptions } from './lib/sync';
export { createPendingQueue, type PendingMessage, type PendingQueue, type PendingState } from './lib/pending';
export { createTypingEmitter, createTypingReceiver } from './lib/typing';
export {
  createWidgetSessionStore,
  ensureSession,
  type StoredWidgetSession,
  type WidgetSessionStore,
} from './lib/session';
