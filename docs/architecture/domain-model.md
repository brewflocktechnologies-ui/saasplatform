# Conversation platform: domain model

Design document (master spec 5 and 8.2). Plan 02 T1 creates the tables of section 3 in
`database/flywayMigration/sql/V3__conversation_platform.sql`; Plans 02, 04 and 05 implement the state machines of
sections 5 and 6. Conventions (Existing, `D<n>`, `R<n>`): [system-overview.md](system-overview.md) section 0.

**Existing:** V1 (`platform_bootstrap`) and V2 (`chat_conversations`, `chat_messages`, no `tenant_id`) are applied and
immutable (`database/flywayMigration/sql/`; [workspace-inventory.md](../research/workspace-inventory.md) section 5).
V3 adds new tables and leaves V2 untouched; V2 is dropped by a later migration only after Plan 06 removes the demo (D3).

## 1. Entities (master spec 5.1)

| # | Entity | Table | Key | Invariants | Class | Owner module |
|---|---|---|---|---|---|---|
| 1 | Tenant | `tenants` | `id text` | Security boundary. Every tenant-owned row carries `tenant_id` and every statement filters on it (D3, R10). The id is also the allocation `X-Tenant-Id` (D8). | MVP | `tenancy` |
| 2 | Inbox | `inboxes` | `id uuid` | Belongs to one tenant. `widget_key` is globally unique and public (it identifies, it does not authorize). `allocation_queue_id` names the allocation queue for its conversations. | MVP | `tenancy` |
| 3 | Contact | `contacts` | `id uuid` | Belongs to one tenant. `external_id` is unique per tenant when present; anonymous contacts have none. | MVP | `tenancy` |
| 4 | Agent (roster) | `agents` | `(tenant_id, id)` | The same agent id may exist in two tenants and they never see each other's data. The id equals the allocation agent id. | MVP | `tenancy` |
| 5 | Conversation | `conversations` | `id uuid` | At most one non-resolved, non-archived conversation per `(tenant, inbox, contact)` (D11). Status, archive marker and assignment projection change independently (section 4). `version` increases on every state write (section 5.4). `last_sequence` equals the highest message `sequence` unless purged (R2). | MVP | `conversation` |
| 6 | ConversationParticipant | `conversation_participants` | `(conversation_id, participant_type, participant_id)` | The contact row is written when the conversation is created; an `AGENT` row on the agent's first message or assignment (D14). Participation never gates an agent of the same tenant from reading or sending (D14). | MVP | `conversation` |
| 7 | Message | `messages` | `id uuid` | `(tenant_id, conversation_id, sequence)` unique and gap-free (D4, R2). `(conversation_id, sender_type, sender_id, client_message_id)` unique (D5). Body 1 to 4000 code points after trim; `message_type = TEXT`. Never updated in the MVP. | MVP | `conversation` |
| 8 | Attachment | `message_attachments` | `id uuid` | Schema only: no upload endpoint, no object storage; purge still deletes the rows (D15, D16). | NEXT | `conversation` |
| 9 | Assignment | projection columns on `conversations` | n/a | The allocation service owns assignment records, history, availability and capacity (D8). The platform stores only `assignment_state`, `assigned_agent_id`, `assignment_decision_id`, `assignment_reason`, `assignment_explanation` plus worker bookkeeping. No `conversation_assignment_history` table (master spec 8.2 condition: the allocation service already owns it). | MVP (projection) | `conversation` |
| 10 | ReadCursor | `conversation_read_cursors` | `(conversation_id, participant_type, participant_id)` | One row per participant, not per message. Monotonic and at most `last_sequence` (R12). | MVP | `conversation` |
| 11 | OutboxEvent | `outbox_events` | `event_id uuid` | One row per durable domain event, written in the transaction of the change (D6). `event_id` is the envelope `eventId`. | MVP | `outbox` |
| 12 | AuditEvent | `conversation_state_events` (+ `purge_runs`) | `id uuid` | Append-only record of lifecycle and assignment changes with actor (R16). `details` never contains message bodies. Survives purge (D16); the purge removes only the free-text `reason` from `details` (R-P31 M10). Authentication and session audit: NEXT. | MVP | `conversation` |
| 13 | Retention policy | `retention_policies` | `tenant_id` | `resolved_retention_days > 0`; `legal_hold` stops every purge for the tenant (D16). | MVP | `retention` |
| 14 | Purge run | `purge_runs` | `id uuid` | One row per run, dry runs included, with counts and status. | MVP | `retention` |

Optional entities (master spec 5.1, last paragraph):

| Entity or feature | Class | Note |
|---|---|---|
| Message edit, message delete (tombstone) | NEXT | Columns `edited_at`, `deleted_at`, `deleted_by`, `deletion_reason` exist; no endpoint ([message-lifecycle.md](message-lifecycle.md) section 7). |
| Replies / threads | NEXT | `messages.reply_to_message_id` exists; nothing writes it. |
| Attachments | NEXT | Row 8 above. |
| Authentication / session audit | NEXT | R16. |
| Snooze (`SNOOZED`) | DEFER | D10. |
| Labels / tags, canned responses, teams and team membership, bot sessions, SLA records, reactions, search index, read receipts | DEFER | Not needed for the message lifecycle; `BOT` and `SYSTEM` sender types are reserved (R9). |
| Per-tenant conversation display number | DEFER | R7; the UUID is the only identifier. |

## 2. Separation of state (master spec 5.2)

| State | Lives in | Changed by | Not to be confused with |
|---|---|---|---|
| Conversation lifecycle | `conversations.status` (`OPEN`, `PENDING`, `RESOLVED`) | agent commands, contact messages (section 5) | archive marker; assignment |
| Archive | `archived_at`, `archived_by`, `archive_reason` | `/archive`, `/unarchive`, purge (`archived_by = 'system:retention'`) | resolution: a resolved conversation is not archived until someone archives it |
| Assignment | projection on `conversations` (section 6); authoritative record in the allocation service | `AssignmentWorker`, `/assign` | sending rights: assignment routes work but does not gate sending (D14) |
| Agent availability | allocation `agent.status` + `last_heartbeat_at` (D9) | `PUT /api/v1/agent/presence` forwarded to the allocation service | connection state; the platform keeps no copy beyond a 5 s read cache |
| Connected presence | the client's Centrifugo socket state; not stored | browser | availability: a connected agent may be `AWAY` |
| Capacity | counted by the allocation service from its active assignments | allocation service | anything the platform computes (it never does) |
| Message persistence | a committed `messages` row: committed means accepted | send transaction | realtime delivery |
| Realtime delivery | `outbox_events.status` (`PENDING`, `PUBLISHED`, `PARKED`) | `OutboxPublisher` | receipt by a browser: `PUBLISHED` only means Centrifugo accepted it |
| Read / unread | `conversation_read_cursors.last_read_sequence`; unread = `last_sequence − cursor` (D13), and 0 once the conversation is purged (R-P31 M5) | `PUT …/read`, sender's own send (R12) | delivery receipts (none in the MVP) |
| Message visibility / deletion | `deleted_at` (reserved, NEXT); purge removes rows | nothing in the MVP; purge | archive |
| Purge | `conversations.purged_at`, exposed as the nullable `ConversationView.purgedAt` (R-P31 M5) | `RetentionService` | archive (purge also archives; archive never purges) |

Examples the model must allow (master spec 5.2): an `OPEN` conversation with `assignment_state = QUEUED`; an agent
connected but `AWAY`; a message committed while its outbox row is still `PENDING` because Centrifugo is down; a
`RESOLVED` conversation that is not archived.

## 3. V3 tables (Plan 02 T1; names are fixed)

Every column is `NOT NULL` unless the table says `null` (the nullable columns are exactly those marked here, R-P7). Ids default to `uuidv7()` unless noted. `tenant_id` is `text`.
Times are `timestamptz`.

| Table | Columns | Keys and checks | Indexes |
|---|---|---|---|
| `tenants` | `id text`, `name text`, `created_at` | PK `id` | |
| `inboxes` | `id uuid`, `tenant_id` → `tenants`, `name`, `widget_key text`, `allocation_queue_id text`, `created_at` | PK `id`; `widget_key` UNIQUE | |
| `contacts` | `id uuid`, `tenant_id` → `tenants`, `external_id text null`, `display_name text null`, `created_at` | PK `id`; UNIQUE `(tenant_id, external_id) WHERE external_id IS NOT NULL` | |
| `agents` | `tenant_id` → `tenants`, `id text`, `display_name`, `created_at` | PK `(tenant_id, id)` | |
| `conversations` | `id uuid`, `tenant_id`, `inbox_id` → `inboxes`, `contact_id` → `contacts`, `status` default `'OPEN'`, `version bigint` default 0, `last_sequence bigint` default 0, `last_message_at null`, `last_message_preview text null`, `assignment_state` default `'NONE'`, `assigned_agent_id text null`, `assignment_decision_id uuid null`, `assignment_reason text null`, `assignment_explanation text null`, `assignment_attempts int` default 0 (monotonic, feeds the idempotency key), `assignment_setup_failures int` default 0 (consecutive setup errors, R-P2), `assignment_next_attempt_at null`, `resolved_at null`, `archived_at null`, `archived_by text null`, `archive_reason text null`, `purged_at null`, `created_at`, `updated_at` | PK `id`; `status IN ('OPEN','PENDING','RESOLVED')`; `assignment_state IN ('NONE','REQUESTED','QUEUED','ASSIGNED','RELEASE_PENDING','REOPEN_PENDING','FAILED')`; partial UNIQUE `ux_conversations_active_per_contact (tenant_id, inbox_id, contact_id) WHERE status <> 'RESOLVED' AND archived_at IS NULL` (D11) | `ix_conversations_inbox_activity (tenant_id, inbox_id, archived_at, status, coalesce(last_message_at, created_at) DESC, id DESC)`; `ix_conversations_agent_activity (tenant_id, assigned_agent_id, archived_at, status, coalesce(last_message_at, created_at) DESC, id DESC)`; partial `ix_conversations_assignment_due (assignment_next_attempt_at) WHERE assignment_state IN ('REQUESTED','QUEUED','FAILED','RELEASE_PENDING','REOPEN_PENDING')` (`FAILED` rows are re-read every `failed-poll`, R-P17); partial `ix_conversations_purge_due (tenant_id, id) WHERE purged_at IS NULL AND (status = 'RESOLVED' OR archived_at IS NOT NULL)`, covering both branches of the purge eligibility rule (R-P25; [archive-retention-purge.md](archive-retention-purge.md) section 2) |
| `conversation_participants` | `conversation_id` → `conversations`, `tenant_id`, `participant_type`, `participant_id text`, `joined_at` | PK `(conversation_id, participant_type, participant_id)`; `participant_type IN ('CONTACT','AGENT','BOT','SYSTEM')` | |
| `messages` | `id uuid`, `tenant_id`, `conversation_id` → `conversations`, `sequence bigint`, `sender_type`, `sender_id text`, `message_type` default `'TEXT'`, `body text`, `client_message_id text null`, `request_fingerprint text null`, `reply_to_message_id uuid null` → `messages`, `metadata jsonb null`, `created_at` default `clock_timestamp()`, `edited_at null`, `deleted_at null`, `deleted_by text null`, `deletion_reason text null` | PK `id`; UNIQUE `(tenant_id, conversation_id, sequence)`; partial UNIQUE `(conversation_id, sender_type, sender_id, client_message_id) WHERE client_message_id IS NOT NULL`; `sender_type IN ('CONTACT','AGENT','BOT','SYSTEM')`; `message_type IN ('TEXT')`; `length(btrim(body)) > 0 AND char_length(body) <= 4000`; `client_message_id IS NULL OR char_length(client_message_id) BETWEEN 1 AND 64` | the two unique indexes (history pages use the first) |
| `message_attachments` | `id uuid`, `tenant_id`, `message_id` → `messages` ON DELETE CASCADE, `file_name`, `content_type`, `size_bytes bigint`, `storage_key text`, `status`, `created_at` | PK `id`; `status IN ('PENDING','READY','DELETED')` | |
| `conversation_read_cursors` | `conversation_id` → `conversations`, `tenant_id`, `participant_type`, `participant_id`, `last_read_sequence bigint` default 0, `updated_at` | PK `(conversation_id, participant_type, participant_id)` | |
| `conversation_state_events` | `id uuid`, `tenant_id`, `conversation_id` → `conversations`, `event_type text`, `actor_type text`, `actor_id text`, `from_status text`, `to_status text`, `details jsonb null`, `created_at` | PK `id` | `(tenant_id, conversation_id, created_at)` |
| `outbox_events` | `event_id uuid` (no default: `OutboxAppender` reads `SELECT uuidv7()` first so the stored envelope carries the same id), `tenant_id`, `aggregate_type text`, `aggregate_id text`, `event_type text`, `schema_version int` default 1, `channels text[]`, `payload jsonb`, `status` default `'PENDING'`, `attempts int` default 0, `next_attempt_at` default `clock_timestamp()`, `locked_by text null`, `locked_until null`, `last_error text null`, `trace_id text null`, `created_at` default `clock_timestamp()`, `published_at null` | PK `event_id`; `status IN ('PENDING','PUBLISHED','PARKED')` | partial `ix_outbox_due (next_attempt_at, created_at) WHERE status = 'PENDING'`; `ix_outbox_aggregate (tenant_id, aggregate_type, aggregate_id)` (purge) |
| `retention_policies` | `tenant_id` PK → `tenants`, `resolved_retention_days int`, `legal_hold boolean` default false, `updated_at` | `resolved_retention_days > 0` | |
| `purge_runs` | `id uuid`, `tenant_id`, `dry_run boolean`, `cutoff`, `status`, `conversations_examined`, `messages_deleted`, `attachments_deleted`, `cursors_deleted`, `batches` (all `int` default 0), `last_error text null`, `started_at`, `finished_at null` | PK `id`; `status IN ('RUNNING','COMPLETED','FAILED')` | |

Notes:
- `conversation_state_events.from_status` and `to_status` hold the lifecycle status before and after the change; they
  are equal for archive, unarchive and assignment events. `details` holds the command's free-text `reason` (removed by
  the purge, R-P31 M10) and, for a `RESOLVED` event, `lastSequence` = the conversation's `last_sequence` at the resolve,
  so race tests compare a message with the resolve by `sequence`, not by timestamps (R-P31 M7).
- Conversation-scoped outbox rows use `aggregate_type = 'conversation'` and `aggregate_id = <conversation id>`, including
  `message.created`; the purge deletes by that pair (Plan 02 T3, Plan 05 T1).
- No composite `(tenant_id, id)` foreign keys and no row-level security in the MVP (R10).
- `COMMENT ON` for `conversations.version`, `last_sequence` and `assignment_state` points to this document.

## 4. Commands and actors

| Command (`ConversationLifecycle.Command`) | Endpoint | Actor |
|---|---|---|
| `RESOLVE` | `POST /api/v1/agent/conversations/{id}/resolve` | agent of the conversation's tenant |
| `REOPEN` | `POST …/reopen` | agent |
| `MARK_PENDING` | `POST …/pending` | agent |
| `ARCHIVE` | `POST …/archive` | agent; the purge sets the same marker as `system:retention` |
| `UNARCHIVE` | `POST …/unarchive` | agent |
| `CONTACT_MESSAGE` | `POST /api/v1/widget/conversations/{id}/messages` | the conversation's contact |
| `AGENT_MESSAGE` | `POST /api/v1/agent/conversations/{id}/messages` | agent |

Command bodies are `CommandRequest(Long expectedVersion, String reason)`; both fields are optional. `reason` is stored in
the state event's `details` (and in `archive_reason` for `ARCHIVE`). A successful or no-op command answers 200 with the
current `ConversationView`.

## 5. Conversation state machine (D10)

### 5.1 Check order

For lifecycle commands, under the conversation row lock (R13):

1. **Already satisfied → 200 no-op**, even when `expectedVersion` is stale (safe client retries). No write, no version
   change, no state event, no outbox row. "Satisfied" means the command's target already holds: status `RESOLVED` for
   `RESOLVE`, `OPEN` for `REOPEN`, `PENDING` for `MARK_PENDING`, archived for `ARCHIVE`, not archived for `UNARCHIVE`. On an
   archived conversation only `RESOLVE` (status `RESOLVED`) and `ARCHIVE` are satisfied no-ops (R-P8); `REOPEN` of an
   archived `OPEN` and `MARK_PENDING` of an archived `PENDING` conversation are not no-ops but 409
   `CONVERSATION_ARCHIVED` at step 3, because an archived conversation accepts no status command (R-P31 M14, amending
   R-P8).
2. **`expectedVersion` present and different from `version` → 409 `STALE_VERSION`.** Two agents sending conflicting
   commands with the same version get one 200 and one `STALE_VERSION` (Plan 07 `PlatformRaceIT.conflictingAgentCommandsWithVersion`).
3. **Transition table** (section 5.2) → apply, or 409 with the table's code. An archived conversation rejects every
   status change and every message with `CONVERSATION_ARCHIVED` before any other table rule, including
   `INVALID_TRANSITION` (R-P8).
4. **Partial unique index** `ux_conversations_active_per_contact` violated by the write → 409
   `CONVERSATION_ACTIVE_EXISTS`, nothing written (D11). Only transitions that make a conversation non-resolved and
   non-archived can hit it: `REOPEN` from `RESOLVED`, `UNARCHIVE` of a non-resolved conversation, and a contact message
   that auto-reopens.

The write itself is `UPDATE conversations … SET version = version + 1 … WHERE id = :id AND version = :lockedVersion`;
0 rows is `STALE_VERSION` (R13). Messages carry no `expectedVersion`; their order inside the send transaction is in
[message-lifecycle.md](message-lifecycle.md) section 1.

### 5.2 Transition table (3 statuses × 2 archive states × 7 commands = 42 cases)

`→ X [Tn]` applies transition `Tn` of section 5.3. `ConversationLifecycleTest` (Plan 02 T3) enumerates all 42 cases
from these two tables.

**Not archived** (`archived_at IS NULL`):

| Command | `OPEN` | `PENDING` | `RESOLVED` |
|---|---|---|---|
| `RESOLVE` | → `RESOLVED` [T1] | → `RESOLVED` [T1] | 200 no-op |
| `REOPEN` | 200 no-op | → `OPEN` [T2] | → `OPEN` [T3], or 409 `CONVERSATION_ACTIVE_EXISTS` |
| `MARK_PENDING` | → `PENDING` [T4] | 200 no-op | 409 `INVALID_TRANSITION` |
| `ARCHIVE` | archived, stays `OPEN` [T5] | archived, stays `PENDING` [T5] | archived, stays `RESOLVED` [T5] |
| `UNARCHIVE` | 200 no-op | 200 no-op | 200 no-op |
| `CONTACT_MESSAGE` | accepted, stays `OPEN` | accepted, → `OPEN` [T6] (R8) | accepted, → `OPEN` [T7], or 409 `CONVERSATION_ACTIVE_EXISTS` |
| `AGENT_MESSAGE` | accepted, stays `OPEN` | accepted, stays `PENDING` (R8) | 409 `CONVERSATION_RESOLVED` |

**Archived** (`archived_at IS NOT NULL`):

| Command | `OPEN` | `PENDING` | `RESOLVED` |
|---|---|---|---|
| `RESOLVE` | 409 `CONVERSATION_ARCHIVED` | 409 `CONVERSATION_ARCHIVED` | 200 no-op |
| `REOPEN` | 409 `CONVERSATION_ARCHIVED` (R-P31 M14) | 409 `CONVERSATION_ARCHIVED` | 409 `CONVERSATION_ARCHIVED` |
| `MARK_PENDING` | 409 `CONVERSATION_ARCHIVED` | 409 `CONVERSATION_ARCHIVED` (R-P31 M14) | 409 `CONVERSATION_ARCHIVED` |
| `ARCHIVE` | 200 no-op | 200 no-op | 200 no-op |
| `UNARCHIVE` | unarchived, stays `OPEN` [T8], or 409 `CONVERSATION_ACTIVE_EXISTS` | unarchived, stays `PENDING` [T8], or 409 `CONVERSATION_ACTIVE_EXISTS` | unarchived, stays `RESOLVED` [T8]; 409 `INVALID_TRANSITION` when `purged_at` is set |
| `CONTACT_MESSAGE` | 409 `CONVERSATION_ARCHIVED` | 409 `CONVERSATION_ARCHIVED` | 409 `CONVERSATION_ARCHIVED` |
| `AGENT_MESSAGE` | 409 `CONVERSATION_ARCHIVED` | 409 `CONVERSATION_ARCHIVED` | 409 `CONVERSATION_ARCHIVED` |

A purged conversation is always `RESOLVED` and archived (D16; the purge also sets `RESOLVED` on an archived conversation
that was not, R-P25): a reopen or message is `CONVERSATION_ARCHIVED`, and an
unarchive is `INVALID_TRANSITION`, so no message can ever be written into a purged conversation (Plan 07
`Invariants.assertAll`: "no `purged_at` conversation with messages"). `ConversationLifecycle.apply(status, archived,
command)` has no purge input; `ConversationService` checks `purged_at` for `UNARCHIVE` after step 2 of section 5.1
(R-P8; Plan 02 `LifecycleIT.unarchivePurgedConversationIs409`).

### 5.3 Transitions and their effects

Every transition increments `version`, sets `updated_at`, writes one state event with the actor and one outbox
`conversation.updated` row (channels: the conversation channel and the inbox channel; [realtime-and-recovery.md](realtime-and-recovery.md)
section 3). When the transition also changes `assignment_state` (last column), that one event covers both changes: no
second `conversation.updated` and no second `version` increment (R-P23).

| Id | Transition | Other column writes | State event `event_type` | `data.change` | `assignment_state` effect |
|---|---|---|---|---|---|
| T1 | `RESOLVE`: `OPEN`/`PENDING` → `RESOLVED` | `resolved_at = now` | `RESOLVED`, `details.lastSequence` = `last_sequence` (R-P31 M7) | `RESOLVED` | `REQUESTED`, `QUEUED`, `ASSIGNED`, `REOPEN_PENDING`, `FAILED` → `RELEASE_PENDING` (a `FAILED` row must not hold engine capacity silently, R-P17); `NONE`, `RELEASE_PENDING` unchanged |
| T2 | `REOPEN`: `PENDING` → `OPEN` | none | `REOPENED` | `REOPENED` | unchanged |
| T3 | `REOPEN`: `RESOLVED` → `OPEN` | `resolved_at` keeps the last resolution time, overwritten by the next resolve (R-P8) | `REOPENED` | `REOPENED` | → `REOPEN_PENDING` |
| T4 | `MARK_PENDING`: `OPEN` → `PENDING` | none | `PENDING` | `PENDING` | unchanged |
| T5 | `ARCHIVE` | `archived_at = now`, `archived_by = <agent id>`, `archive_reason = reason` | `ARCHIVED` | `ARCHIVED` | `REQUESTED`, `QUEUED`, `ASSIGNED`, `REOPEN_PENDING`, `FAILED` → `RELEASE_PENDING`: an archived conversation holds no allocation capacity (R-P13, R-P17); `NONE`, `RELEASE_PENDING` unchanged |
| T6 | contact message: `PENDING` → `OPEN` | (message writes) | `REOPENED_BY_CONTACT` | `REOPENED` | unchanged (R8) |
| T7 | contact message: `RESOLVED` → `OPEN` | (message writes) | `REOPENED_BY_CONTACT` | `REOPENED` | → `REOPEN_PENDING` |
| T8 | `UNARCHIVE` | `archived_at`, `archived_by`, `archive_reason` = null | `UNARCHIVED` | `UNARCHIVED` | status `OPEN` or `PENDING`: → `REOPEN_PENDING` (the worker requeues the engine record or keeps the agent, as for a reopen; R-P13); status `RESOLVED`: unchanged |

Every move into `REQUESTED`, `RELEASE_PENDING` or `REOPEN_PENDING` also sets
`assignment_next_attempt_at = GREATEST(assignment_next_attempt_at, :now)`: due at once, while a running worker lease is
kept (Plan 02 T3). The one exception is a move out of `FAILED` (T1 and T5 to `RELEASE_PENDING`, T8 to
`REOPEN_PENDING`, `/assign` to `REQUESTED`): it sets `assignment_next_attempt_at = :now`, because a `FAILED` row's time is
the `failed-poll` schedule (up to 60 s ahead), not a worker lease, and `GREATEST` would delay the release or requeue by up
to `failed-poll`. A `FAILED` re-read in flight at that moment cannot project over the new state: every projection
carries an `assignment_state` precondition (R-P20).

### 5.4 `version`

`version` starts at 0 (`conversation.created` carries 0) and increments (`version = version + 1` in the same `UPDATE`)
**if and only if** the same transaction appends a `conversation.updated` outbox row (R-P23): on every write that changes
the conversation's status or archive marker, and on every projection write that changes `(assignment_state,
assigned_agent_id)` (R-P9, R-P23). The one exception is the purge, whose `UPDATE` adds `version = version + 1` and
emits no realtime event (R-P9, R-P25). `version` does not change for message activity (`last_sequence`,
`last_message_at`, `last_message_preview`), which `sequence` orders (R2), nor for worker bookkeeping (claims,
`assignment_attempts`, `assignment_setup_failures`, `assignment_next_attempt_at`, and a projection that leaves
`(assignment_state, assigned_agent_id)` unchanged, such as a `QUEUED` poll that finds the record still `QUEUED` and only
updates the stored reason), so neither message activity nor a silent worker move makes an agent's `expectedVersion`
stale. Because every other increment comes with exactly one event, a client that sees a `version` jump greater than 1
has missed an event (or the conversation was purged) and refetches the conversation (R3).

## 6. Assignment state machine (D8)

The allocation service owns the assignment; `assignment_state` is the platform's view of where the request stands.
The worker's call sequence and outcome handling per state are in [allocation-integration.md](allocation-integration.md)
section 3.

| State | Meaning | Entered from | Left to | Worker claims it? |
|---|---|---|---|---|
| `NONE` | nothing requested: the column default, and the state after a confirmed release | `RELEASE_PENDING` (release confirmed) | `REOPEN_PENDING` (T3, T7, T8) | no |
| `REQUESTED` | durable request to allocate; the allocation record may not exist yet | conversation created by `POST /widget/conversations`; `REOPEN_PENDING` (record absent or requeued); `QUEUED` (allocation lost the record); a `CONFLICT_RETRYABLE` outcome (R-P1); `/assign` projecting an engine record that is absent or `RESOLVED` (`NOT_ASSIGNED` or `CONVERSATION_NOT_ALLOCATABLE`, R-P14, R-P17) | `ASSIGNED`, `QUEUED`, `FAILED`, `RELEASE_PENDING` (T1, T5) | yes, unless archived; the only state that dispatches |
| `QUEUED` | the allocation service holds the conversation in its queue; the worker only polls (R-P1) | a `QUEUED_*` outcome (worker or `/assign`); `REOPEN_PENDING` when the allocation record is `QUEUED`; `/assign` projecting a `QUEUED` engine record (R-P14, R-P17) | `ASSIGNED` (dispatch, claim or sweep), `REQUESTED`, `FAILED`, `RELEASE_PENDING` (T1, T5) | yes, unless archived, every `queued-poll` (5 s) |
| `ASSIGNED` | the allocation service assigned `assigned_agent_id` | an `ASSIGNED` outcome or a poll that finds the record assigned (a `QUEUED` poll, a `FAILED` re-read, or `/assign`); `REOPEN_PENDING` when the record is still assigned (reason `REOPEN_KEPT_AGENT`) | `RELEASE_PENDING` (T1, T5); `/assign` `REASSIGN` (→ `ASSIGNED` or `QUEUED`; when the engine answers `NOT_ASSIGNED`, the projected engine state: → `REQUESTED` when the record is absent or `RESOLVED`, → `FAILED` when it is `CANCELLED`, R-P14) | no: no background reconciliation in the MVP (R-P14) |
| `RELEASE_PENDING` | resolve or archive must reach the allocation service to free capacity | T1, T5 (from `REQUESTED`, `QUEUED`, `ASSIGNED`, `REOPEN_PENDING` and, since R-P17, `FAILED`) | `NONE` (release confirmed); `REOPEN_PENDING` (T3, T7, T8 before the release ran); never `FAILED` (R-P14) | yes, archived or not |
| `REOPEN_PENDING` | the conversation was reopened or unarchived; the allocation record must be requeued or kept | T3, T7, T8 | `REQUESTED`, `QUEUED`, `ASSIGNED`, `FAILED`; `RELEASE_PENDING` (T1, T5) | yes |
| `FAILED` | the request cannot proceed without a person: a rejection, a configuration fault, or 30 consecutive setup errors (`assignment_setup_failures`, R-P2) | worker outcomes ([allocation-integration.md](allocation-integration.md) section 3); `/assign` projecting a `CANCELLED` engine record or a `FAILED_INTERNAL`, `SKIPPED_POLICY_DISABLED` or `NO_WAITING_CONVERSATION` outcome ([allocation-integration.md](allocation-integration.md) section 5) | `ASSIGNED` (the periodic check finds the engine record assigned: reason `ALLOCATION_SWEEP`, R-P17); `/assign` (any mode) → its outcome; `REOPEN_PENDING` (T3, T7, T8); `RELEASE_PENDING` on resolve and archive (T1, T5, R-P17) | yes, unless archived, every `app.assignment.failed-poll` (60 s) for a `findConversation`-only check that never dispatches (R-P17); listed in `view=queue` so an agent can retry |

Rules: every projection write has an `assignment_state` precondition and, when it changes `(assignment_state,
assigned_agent_id)`, increments `version` and appends exactly one `conversation.updated` (R-P23); 0 rows means the row
moved on (for example, resolved while the worker was calling the allocation service) and the result is dropped (R13).
The worker's bookkeeping writes (`reschedule`, `recordSetupFailure`, `resetSetupFailures`) carry the same
`assignment_state` precondition (R-P31 M15).
The worker claims `REQUESTED`, `QUEUED` and `FAILED` rows only when they are not archived, and never dispatches an
archived row; only `REQUESTED` rows dispatch, `QUEUED` rows are re-read with `findConversation` every `queued-poll` (5 s)
and `FAILED` rows every `failed-poll` (60 s) (R-P1, R-P17); `RELEASE_PENDING` and `REOPEN_PENDING` rows are always
claimable (R-P13). A row entering `FAILED`, and each re-read that leaves it `FAILED`, gets
`assignment_next_attempt_at = now + failed-poll`. `assignment_setup_failures` counts consecutive setup-code rejections
and is reset to 0 by any successful allocation response; `assignment_attempts` only grows because it feeds the
idempotency key (R-P2).
Projection writes also upsert the `AGENT` participant on assignment and append `conversation.updated` with `change`
`ASSIGNED`, `ASSIGNMENT_QUEUED` (on entering `QUEUED`) or `ASSIGNMENT_FAILED`, and `ASSIGNMENT_UPDATED` for every other
projection change: the release to `NONE` and projection moves into `REQUESTED`, `RELEASE_PENDING` or `REOPEN_PENDING`
(R-P23). The lifecycle transitions T1, T3, T5, T7 and T8 move the assignment under their own lifecycle event (section
5.3). State events: `ASSIGNED`, `ASSIGNMENT_QUEUED`, `ASSIGNMENT_FAILED`, `RELEASED`.

Visibility (R-P3, R-P29): `AssignmentView` carries `state`, `agentId`, `decisionId`, `reason` and `explanation` only for
agent callers (`GET /api/v1/agent/…`; null when unknown). For contact callers and in every `conversation.created` /
`conversation.updated` event payload, `assignment` is the dedicated two-field record `ContactAssignmentView(state,
agentId)`: both keys are always present (`agentId` null when no agent holds the conversation), and `decisionId`,
`reason` and `explanation` do not exist in it, because the conversation channel is shared with the visitor and
allocation explanations describe internal routing. R-P29 supersedes R-P21's serialisation detail (no `AssignmentView`
with null fields dropped).

## 7. Identifiers and time

| Identifier | Format | Assigned by | Rule |
|---|---|---|---|
| conversation, message, contact and inbox ids; ids of `message_attachments`, `conversation_state_events`, `purge_runs` | UUID v7 | PostgreSQL `uuidv7()` (PostgreSQL 18) | never accepted from a client |
| outbox `event_id` = envelope `eventId` | UUID v7 | `SELECT uuidv7()` by `OutboxAppender` before the insert | identical on every redelivery |
| tenant id | `text` | admin or seed | matches `^[a-z0-9][a-z0-9-]{0,62}$` (Plan 05 T2: no `:` because it is part of `chat:inbox:<tenantId>:<inboxId>` and the Centrifugo `sub`); also passes the allocation id rule `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}` ([allocation-service-integration.md](../research/allocation-service-integration.md) section 2.5) |
| agent id | `text`, unique per tenant | admin or seed | passes the allocation id rule |
| `widget_key` | `text`, globally unique, public | admin or seed | identifies an inbox; not a secret |
| `clientMessageId` | 1 to 64 characters | the browser (`crypto.randomUUID()` in `libs/chat-client`) | scoped by `(conversation, sender type, sender id)` (D5) |
| `sequence` | `bigint` from 1 per conversation | `last_sequence + 1` under the row lock | gap-free, messages only (D4, R2) |
| `version` | `bigint` from 0 per conversation | `version + 1` in the state write | section 5.4 |
| allocation `decisionId` | UUID text from the allocation service | allocation service | stored as `assignment_decision_id`; `null` for sweep assignments |
| Centrifugo stream offset and epoch | Centrifugo | Centrifugo | never used as an application id (master spec 9.3) |

Timestamps are server-generated, never taken from a client: `messages.created_at` is the database
`clock_timestamp()`; times that tests must control (session `exp`, outbox `next_attempt_at` and `occurredAt`,
`assignment_next_attempt_at`, purge cutoff, lifecycle timestamps) come from the injected `Clock` bean (**existing:**
`shared/ClockConfig.java`). The API returns ISO-8601 instants with offset (`OffsetDateTime`).
