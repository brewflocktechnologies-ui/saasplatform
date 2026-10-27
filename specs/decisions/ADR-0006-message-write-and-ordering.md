# ADR-0006: Message write path and per-conversation ordering

- **Status:** Accepted (design); correctness validation pending in Plan 02 (`MessageWriteIT`,
  `MessageConcurrencyIT`), cost validation pending in Plan 07 (load profiles, lock sampling)
- **Date:** 2026-10-27
- **Master spec name:** ADR-002 "message write and ordering"
- **Details:** [message-lifecycle.md](../../docs/architecture/message-lifecycle.md)

## Context

Master spec 5.4 asks for an explicit ordering choice, 5.5 for idempotent creation and 8.1 for one transaction that
writes the message and its outbox event. The client recovery protocol
([realtime-and-recovery.md](../../docs/architecture/realtime-and-recovery.md)) needs to detect a missing message, which
requires a gap-free counter. **Existing:** the demo orders by `(created_at, id)`, has no sequence and no idempotency key,
so a retried send creates a second message (`chat/ChatService.java`; workspace-inventory.md section 8.2). None of the
reference systems has a gap-free per-conversation sequence or a persisted client message id
([reference-comparison.md](../../docs/research/reference-comparison.md) section 3): Chatwoot orders by `created_at`,
Rocket.Chat by a millisecond timestamp, Zulip by a global id that is not commit-ordered.

## Options

1. **Counter on the conversation row under its row lock**: `SELECT … FOR UPDATE` on the conversation, then
   `last_sequence = last_sequence + 1` in the same transaction.
2. **Timestamp plus id** as order and cursor (`created_at, id`). No serialisation, but concurrent sends can commit out
   of timestamp order, so a client cursor can skip a row, and gaps cannot be detected.
3. **A PostgreSQL `SEQUENCE` or identity** (global or per conversation). No row lock, but values are consumed by
   rolled-back transactions (gaps) and are not commit-ordered.
4. **Optimistic insert** of `max(sequence) + 1` with a unique `(conversation, sequence)` index and retry on conflict.
   Gap-free without a held lock, but retries under contention and a second code path for every writer.
5. **A separate counter table or advisory lock per conversation.** Same serialisation as option 1 with one more object
   to keep consistent.

## Decision

Option 1 (overview D4, ruling R2), plus D5 idempotency:

- The send transaction (READ COMMITTED) is: lock the conversation row → participant check → lifecycle policy →
  idempotency lookup → `last_sequence + 1` → insert message → update the conversation (activity, transition, sender
  participant, sender read cursor) → outbox row → state event → commit. Nothing in it calls the network.
- `sequence` is gap-free, numbers messages only, and is the history cursor (`before=` / `after=`). Conversation events
  carry `version` instead (R3).
- `clientMessageId` (1 to 64 characters) is required from browser clients; unique per `(conversation_id, sender_type,
  sender_id, client_message_id)`; `request_fingerprint = sha256(trimmed body)`. Same key and body → the original message
  with 200; same key, different body → 409 `CLIENT_MESSAGE_ID_REUSED`.
- Lifecycle commands use the same row lock plus a guarded `UPDATE … WHERE version = :expected` (R13).
- The serialisation cost per conversation is accepted for the MVP because chat concurrency is spread across
  conversations; it is measured, not assumed.

## Consequences

- Commit order equals sequence order; a rolled-back send leaves no hole; clients detect gaps exactly and never need
  timestamps for ordering.
- Sends to one conversation queue on its row lock for the length of the transaction (a few statements, no network).
  The same row is also locked by lifecycle commands and assignment projections, so a busy conversation's commands wait
  behind its sends.
- Every transaction that writes a conversation locks that one row first and takes no other row lock first, which keeps
  lock ordering simple and avoids deadlocks between these writers.
- Idempotency costs one unique index, one SHA-256 per send and one indexed lookup; a replay writes nothing.
- A retry after the conversation was archived (or resolved, for agents) gets the lifecycle 409 even though the original
  message is stored, because the lifecycle check precedes the lookup; clients reconcile through history.

## Operational impact

- Lock waits on `conversations` and deadlocks are the signals to watch: Plan 07 samples `pg_locks WHERE NOT granted` and
  the `pg_stat_database.deadlocks` delta every 5 s during load runs, next to `message.send.duration`.
- The history index is the unique `(tenant_id, conversation_id, sequence)`; Plan 07 T4 checks with
  `EXPLAIN (ANALYZE, BUFFERS)` that history pages use it.

## Evidence that would trigger a change

- Plan 07 `baseline` or `burst` misses its send threshold (p95 < 300 ms, burst p95 < 800 ms) and lock-wait samples show
  the waits on `conversations` rows, or any deadlock involving `conversations` appears.
- A product need for many concurrent senders in one conversation (group rooms, broadcast channels, bots posting
  bursts): consider option 4 or a counter table, keeping the gap-free contract for clients.
- A need to hold the lock across a network call (for example synchronous moderation): that is a design error under this
  ADR; move the call out of the transaction instead.
