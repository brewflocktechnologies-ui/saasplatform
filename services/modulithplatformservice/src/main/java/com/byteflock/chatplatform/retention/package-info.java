/**
 * Retention (Plan 05): per-tenant policy and the manual, bounded purge of old conversation content. The purge keeps the
 * conversation row and its state events, archives and resolves the conversation, and deletes messages, attachments, read
 * cursors and the conversation's outbox rows (D16, R-P25, R-P31 M10). Admin-only; nothing is scheduled in the MVP.
 */
package com.byteflock.chatplatform.retention;
