/**
 * Versioned custom-element registration entry point (embed API v1).
 * Built to dist/apps/chatwidget/embed/chat-platform-widget.v1.js for future script-tag embedding:
 *   <link rel="stylesheet" href=".../tokens.css" />
 *   <script type="module" src=".../embed/chat-platform-widget.v1.js"></script>
 *   <chat-platform-widget></chat-platform-widget>
 * Breaking changes to element names/attributes require a new v2 entry; v1 stays until consumers migrate.
 * No CDN pipeline yet (deferred).
 */
import { defineCpStatusBadge } from '@chat-platform/component-library-web';
import { ChatPlatformWidget } from '../chat-platform-widget';

export const WIDGET_EMBED_API_VERSION = '1';
export const WIDGET_TAG = 'chat-platform-widget';

defineCpStatusBadge();
if (!customElements.get(WIDGET_TAG)) customElements.define(WIDGET_TAG, ChatPlatformWidget);

export { ChatPlatformWidget };
