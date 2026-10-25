// Framework-agnostic chat MVP client (no React, no Lit): used by both the dashboard and the widget.
export * from './lib/types';
export { ApiError, createChatApi, type ChatApi } from './lib/api';
export { mergeMessages } from './lib/messages';
export {
  CONNECTION_LABEL,
  createRealtime,
  type ConnectionState,
  type Realtime,
  type SubscribeHandlers,
} from './lib/realtime';
