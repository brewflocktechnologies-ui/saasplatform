# Conversation platform: message lifecycle

Design document (master spec 5.4, 5.5, 8.1, 8.4, 10.1, 10.2). Plan 02 T3 implements it in
`conversation/internal/MessageService`, `HistoryService`, `ReadCursorService` and the repositories named below.
**Implementation status:** implemented and covered by `MessageWriteIT`, `MessageConcurrencyIT`, `HistoryIT`,
`ReadCursorIT` and `ConversationApiIT`; the outbox publisher that delivers the rows to Centrifugo is Plan 03.
Conventions (Existing, `D<n>`, `R<n>`): [system-overview.md](system-overview.md) section 0. Decision record:
[ADR-0006](../../specs/decisions/ADR-0006-message-write-and-ordering.md).

**Existing (replaced):** the demo send path (`chat/ChatService.java`) inserts the message and touches the conversation
in one `TransactionTemplate`, then publishes to Centrifugo after commit with no retry and no idempotency key
([workspace-inventory.md](../research/workspace-inventory.md) sections 4.1, 5.1). The design below replaces it.

## 1. Send transaction

`POST /api/v1/widget/conversations/{id}/messages` and `POST /api/v1/agent/conversations/{id}/messages`, body
`SendMessageRequest(clientMessageId, body)`, answer 201 `MessageView` (new) or 200 `MessageView` (replay).

Before the transaction (no database write):

| Step | Check | Failure |
|---|---|---|
| a | **Resolve the caller.** `AuthInterceptor` verifies the bearer session token and builds `Caller(tenantId, type, principalId, inboxId)`; the tenant comes from the token, never from the request ([tenant-security.md](tenant-security.md) section 2). | 401 `UNAUTHENTICATED`; 403 `FORBIDDEN` for the wrong principal type |
| b | **Rate limit** in the controller: 30 messages per minute per principal, bucket key `msg:<tenantId>:<principalType>:<principalId>` so the same agent id in two tenants never shares a bucket (D17, R-P31 M3). | 429 `RATE_LIMITED` |
| c | **Validate** `clientMessageId` (1 to 64 characters) and `body` (trimmed, 1 to 4000 code points) (section 6). | 400 `VALIDATION_FAILED` |

One `TransactionTemplate` transaction at READ COMMITTED (D3), in this order:

| # | Step | Statement (sketch) | Exit |
|---|---|---|---|
| 1 | **Lock the conversation** | `SELECT … FROM conversations WHERE tenant_id = :tenant AND id = :id FOR UPDATE` (`ConversationRepository.lockForUpdate`) | 404 `NOT_FOUND` / 403 `FORBIDDEN` ([tenant-security.md](tenant-security.md) section 4) |
| 2 | **Participant check** | contact: `contact_id = caller.principalId AND inbox_id = caller.inboxId`; agent: same tenant (already filtered) (D14) | 403 `FORBIDDEN` |
| 3 | **Idempotency lookup**, before the lifecycle policy (R-P24) | `SELECT … FROM messages WHERE conversation_id = :id AND sender_type = :t AND sender_id = :s AND client_message_id = :k` (`MessageRepository.findByClientMessageId`) | same fingerprint → commit with no writes, 200 with the original, even if the conversation has since been resolved or archived; different → 409 `CLIENT_MESSAGE_ID_REUSED` |
| 4 | **Lifecycle policy** | `ConversationLifecycle.apply(status, archived, CONTACT_MESSAGE or AGENT_MESSAGE)`: decides the transition (T6, T7 or none) but writes nothing yet ([domain-model.md](domain-model.md) section 5) | 409 `CONVERSATION_ARCHIVED`, `CONVERSATION_RESOLVED` |
| 5 | **Next sequence** | `UPDATE conversations SET last_sequence = last_sequence + 1 WHERE id = :id RETURNING last_sequence` (`bumpSequence`) | none |
| 6 | **Insert the message** | `INSERT INTO messages (…, sequence, sender_type, sender_id, body, client_message_id, request_fingerprint) …` | check violation → rollback (validation already ran) |
| 7 | **Update the conversation** | `touchActivity`: `last_message_at` = the message's `created_at`, `last_message_preview` = first 120 code points of the body; apply T6/T7 if decided in step 4 (`status = 'OPEN'`, `version = version + 1`, T7 also `assignment_state = 'REOPEN_PENDING'` and `assignment_next_attempt_at = GREATEST(assignment_next_attempt_at, :now)`, [domain-model.md](domain-model.md) section 5.3); upsert the sender's participant row (`INSERT … ON CONFLICT DO NOTHING`; an agent's first message adds its `AGENT` row); advance the sender's read cursor to the new `sequence` (R12) | unique violation of `ux_conversations_active_per_contact` on T7 → rollback, 409 `CONVERSATION_ACTIVE_EXISTS` (D11) |
| 8 | **Outbox** | `OutboxAppender.append(tenant, "conversation", <id>, "message.created", [conversation channel, inbox channel], {message: MessageView})`; with T6/T7 also `conversation.updated` (`change = REOPENED`) on the same two channels | `IllegalStateException` if no transaction is active (a bug) |
| 9 | **State event** | with T6/T7: `REOPENED_BY_CONTACT`, actor `CONTACT` | none |
| 10 | **Commit** and answer 201 with the persisted `MessageView` (server `id`, `sequence`, `createdAt`) | | |

Rules:
- Steps 7 to 9 run in one transaction, so their relative order is not observable; Plan 02 T3 writes the state event
  before the outbox row, which is equivalent.
- Steps 3 to 7 address rows by the conversation id that step 1 obtained through the tenant-filtered lock; statements
  keyed only by primary key are allowed inside the transaction after such a lock (R-P6 amended). The only statement on
  a tenant-owned table without a tenant filter is the authorization probe of
  [tenant-security.md](tenant-security.md) section 4.
- Any exception rolls back every write: no message without its outbox row, no outbox row without its message, and no
  consumed sequence number (`MessageWriteIT.rollbackLeavesNoPartialState`).
- Nothing in the transaction calls Centrifugo, the allocation service or any network (overview §4). Publication is the
  outbox's job ([realtime-and-recovery.md](realtime-and-recovery.md) section 4); the reassignment of a reopened
  conversation is the worker's job ([allocation-integration.md](allocation-integration.md)).
- The acknowledgement never waits for Centrifugo: a committed message is answered 201 even when Centrifugo is down
  (master spec 6).

## 2. Idempotency contract (D5)

Browser clients must send `clientMessageId`. The key is unique per `(conversation_id, sender_type, sender_id,
client_message_id)` (partial unique index). Because conversation ids are globally unique, this scope is equivalent to
"tenant, conversation and sender" ([reference-comparison.md](../research/reference-comparison.md) section 2, item 5).
`request_fingerprint` is the lowercase hex SHA-256 of the UTF-8 trimmed body (`Fingerprints.sha256Hex`).

| Request | Result |
|---|---|
| New `clientMessageId` | 201, new message, one `message.created` outbox row |
| Same key, same trimmed body | 200 with the original `MessageView`; no new row, no outbox row, no sequence consumed |
| Same key, different trimmed body | 409 `CLIENT_MESSAGE_ID_REUSED`; nothing written |
| Same key from another sender, or in another conversation | independent message |
| Key missing, empty or longer than 64 characters | 400 `VALIDATION_FAILED` |
| Many identical requests at once | the row lock serialises them: one 201, every other caller 200 with the same id (`MessageConcurrencyIT.identicalConcurrentSendsProduceOneRow`). The unique index is the last defence and is not reached while the lock is held. |
| Retry after the conversation changed state | the lookup (step 3) runs before the lifecycle policy (step 4), so a retry with the same key and body returns the original (200) even after the conversation was archived or, for an agent, resolved, and the pending queue marks the bubble sent (R-P24; master spec 5.5; `MessageWriteIT.replayAfterArchiveReturnsOriginal`, `replayAfterResolveReturnsOriginalForAgent`). A purged conversation has no messages, so a retry there falls through to the lifecycle policy: 409 `CONVERSATION_ARCHIVED`. |

Counter `message.send{outcome=created|replayed|conflict|rejected}` (Plan 05 T3).

## 3. Ordering (D4, R2)

- `sequence = last_sequence + 1`, computed by the `UPDATE` of step 5 while the conversation row is locked. Commit order
  equals sequence order within a conversation, and the committed messages of a conversation are numbered 1, 2, 3 with no
  gap: a rolled-back send also rolls back its `last_sequence` increment. It is not a PostgreSQL `SEQUENCE`, whose values
  can skip.
- `sequence` numbers messages only; conversation events carry `version` (R3). Tombstones (NEXT) will keep their
  sequence; purge removes a conversation's messages wholesale and archives it, so no conversation ever has a hole.
- Sends to one conversation serialise on the row lock; sends to different conversations run in parallel
  (`MessageConcurrencyIT.concurrentSendsAcrossConversationsDoNotSerialize`). The cost and its measurement trigger are in
  ADR-0006.
- Clients display and deduplicate by `sequence` (then `id`), never by `createdAt`. Realtime events can arrive out of
  order after a publisher retry, so clients rely on `sequence`, not on arrival order
  ([realtime-and-recovery.md](realtime-and-recovery.md) section 5).

## 4. History (D13, R11)

`GET /api/v1/widget/conversations/{id}/messages` and `GET /api/v1/agent/conversations/{id}/messages` →
`MessagePage {items: MessageView[], hasMore}`, items ascending by `sequence`.

| Request | Meaning | Statement (one `SELECT`, R11) |
|---|---|---|
| no cursor | newest page | `… WHERE tenant_id = :t AND conversation_id = :c ORDER BY sequence DESC LIMIT :limit + 1`, reversed |
| `before=<seq>` | older page (scroll back), default `limit` 50 | `… AND sequence < :before ORDER BY sequence DESC LIMIT :limit + 1`, reversed |
| `after=<seq>` | catch-up after reconnect; the client sends `limit=200` (the server default stays 50) | `… AND sequence > :after ORDER BY sequence ASC LIMIT :limit + 1` |

- `hasMore` is true when the extra row exists (the `count + 1` probe); for `after` it means "more newer rows", for the
  other two "more older rows".
- `limit` defaults to 50, must be at least 1 (`limit=0` → 400), and is clamped to 200 (`app.limits.history-page-max`):
  `limit=10000` returns 200 items, not an error (`HistoryIT.pagingBeyondRangeAndOversizedLimitAreBounded`). `before` and
  `after` together are 400 `VALIDATION_FAILED`; no cursor returns the newest page (R-P11). A cursor beyond the range
  returns an empty page with `hasMore = false`.
- No `OFFSET`; the cursor is `sequence` and the index is the unique `(tenant_id, conversation_id, sequence)`.
- The conversation head (`lastSequence`) is in `ConversationView`, not in `MessagePage` (overview §5).
- The same participant rules as sending apply; a caller of another tenant gets 403 and never rows
  (`HistoryIT.historyIsTenantScoped`).

Inbox listing for agents: `GET /api/v1/agent/inboxes/{inboxId}/conversations?view=queue|mine|all&archived=false|true&cursor=&limit=`
→ `{items: ConversationView[], nextCursor}`, ordered by `coalesce(last_message_at, created_at) DESC, id DESC`;
`nextCursor` is opaque base64 of `(activityAt, id)` (`Cursors`), `limit` at most 100 (`app.limits.inbox-page-max`).
`view=queue` = `assignment_state IN ('REQUESTED','QUEUED','FAILED','REOPEN_PENDING')` (overview §7 D; a reopened or
unarchived conversation waiting for its requeue is waiting work too, R-P31 M2); `view=mine` =
`assigned_agent_id = caller`; `view=all` = every conversation of the inbox; `archived=true` lists only archived ones.
Summary and preview come from the `conversations` row; the inbox never loads message history (master spec 10.1).

## 5. Read state (D13, R12)

- `PUT /api/v1/{widget|agent}/conversations/{id}/read` with `{lastReadSequence}` → 204.
- Lock order (R-P26): `ReadCursorService` first takes a tenant-filtered `SELECT last_sequence FROM conversations WHERE
  tenant_id = :t AND id = :id FOR KEY SHARE`, then upserts the cursor, so cursor writes and sends lock in the same order
  (conversation, then cursor). Without it, a first read (whose cursor insert takes the conversation's key-share lock
  through the foreign key) and a first send by the same participant (conversation `FOR UPDATE`, then its own cursor
  upsert, R12) wait for each other: deadlock `40P01` (`ReadCursorIT.firstReadRacingFirstSendDoesNotDeadlock`). Cost: a
  cursor write waits for an in-flight send in that conversation.
- Write: `INSERT INTO conversation_read_cursors … ON CONFLICT (conversation_id, participant_type, participant_id) DO
  UPDATE SET last_read_sequence = GREATEST(conversation_read_cursors.last_read_sequence, :clamped)` where `:clamped` is
  `LEAST(:reported, conversations.last_sequence)`. The cursor never moves backwards and never passes the head
  (`ReadCursorIT.advanceIsMonotonic`, `cursorCannotExceedLastSequence`).
- `ConversationView.unreadCount` = `last_sequence − last_read_sequence` for the calling participant (0 when no row
  exists and `last_sequence` is 0), and 0 when `purged_at` is set: the purge deletes the messages and the cursors but
  keeps `last_sequence` (R-P31 M5). It is per caller, which is why events omit it (R14).
- The sender's cursor advances inside the send transaction (step 7), so own messages never count as unread
  (`ReadCursorIT.ownMessagesAreNotUnread`).
- Clients debounce the write (1 s, while the thread is visible; Plan 06). Reading one conversation never touches
  another (`ReadCursorIT.readingOneConversationDoesNotTouchOthers`).

## 6. Validation limits

| Input | Rule | Config | Error |
|---|---|---|---|
| `body` | trimmed; 1 to 4000 Unicode code points (an emoji counts once); also a database check | `app.limits.message-length=4000` | 400 `VALIDATION_FAILED` |
| `messageType` | `TEXT` only (the API has no field; the server sets it) | none | n/a |
| `clientMessageId` | required; 1 to 64 characters | none | 400 |
| history `limit` | 1 to 200, larger values clamped | `app.limits.history-page-max=200` | 400 for < 1 |
| inbox `limit` | 1 to 100 | `app.limits.inbox-page-max=100` | 400 for < 1 |
| `before` / `after` | non-negative integer; at most one of them | none | 400 |
| `lastReadSequence` | non-negative integer; clamped to the head | none | 400 |
| typing | `{typing: boolean}`; 1 per 2 s per principal and conversation | `app.rate-limits.typing-interval=2s` | 429 |
| message rate | 30 per minute per principal (key `msg:<tenantId>:<principalType>:<principalId>`, R-P31 M3) | `app.rate-limits.messages-per-minute=30` | 429 |
| JSON | malformed body, wrong types, unknown route | none | status-derived `code` (overview §5) |

Every error is RFC 9457 `application/problem+json` with `code` and `traceId`, and never contains a stack trace, SQL,
a token or a message body (overview §4, §5). Logs never contain bodies (Plan 05 `LogHygieneTest`).

## 7. Delete, purge and what is not in the MVP

| Capability | MVP? | Why |
|---|---|---|
| Message edit | NEXT | No endpoint in overview §5. `edited_at` is reserved. An edit would need its own event type and an audit rule. |
| Message delete (hide) | NEXT | No endpoint. A future delete is a tombstone: `deleted_at`, `deleted_by`, `deletion_reason` set, body replaced, `sequence` kept, so gap detection stays exact (R2). |
| Purge (irreversible erasure) | MVP, admin only | Hard-deletes all messages, attachment rows, read cursors and outbox rows of eligible conversations (resolved, or archived, before the cutoff; R-P25) in bounded batches (D16). It is conversation-wide; there is no per-message hard delete. Details: [archive-retention-purge.md](archive-retention-purge.md). |
| Attachments | NEXT | `message_attachments` exists; no upload endpoint, no object storage (D15). Not needed to prove ordering, idempotency and the outbox. |
| Replies | NEXT | `reply_to_message_id` exists; nothing writes it. |
| `metadata` | not written | Column reserved for a small validated payload. |
| `BOT` / `SYSTEM` senders | reserved | R9; system notices are state events, not messages. |
