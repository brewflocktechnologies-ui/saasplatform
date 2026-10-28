/**
 * Conversations: the durable write path (ordering, idempotent send), lifecycle (resolve, reopen, pending, archive,
 * unarchive), participants, history pages, read cursors and the assignment projection. Assignment itself is executed by
 * the allocation service (Plan 04); this module stores only the projection.
 * <p>
 * Every command runs in one transaction: the conversation row is locked first, state and message writes share it, and
 * the transactional outbox row is appended in the same transaction so realtime delivery can never be lost.
 */
package com.byteflock.chatplatform.conversation;
