# Conversation platform: realtime delivery and recovery

Design document (master spec 9, 10.3, 10.4, 11.3). Plan 03 implements the server side (`outbox`, `realtime`, channel
authorization, typing, event schemas), Plan 06 the client side (`libs/chat-client` `sync.ts`, `pending.ts`,
`typing.ts`). Conventions (Existing, `D<n>`, `R<n>`): [system-overview.md](system-overview.md) section 0. Decision
record: [ADR-0007](../../specs/decisions/ADR-0007-transactional-outbox-and-centrifugo.md).

**Delivery is at least once.** A committed event is published eventually (or parked for an operator), and it can reach
a client more than once. Clients make duplicates harmless with stable ids; nothing in this design depends on a client
receiving an event exactly one time, and nothing depends on Centrifugo history for durability: PostgreSQL is the
source of truth and every client reconciles from the API.

**Existing (replaced):** the demo publishes after commit with `POST /api/publish`, at most once, no retry, no history
or recovery configured; clients reload history on every `subscribed` and merge by message id
(`realtime/RealtimePublisher.java`, `libs/chat-client/src/lib/realtime.ts`, `infra/centrifugo/config.json`;
[workspace-inventory.md](../research/workspace-inventory.md) sections 4.2 to 4.6). Kept: namespace `chat` with client
subscribe and client publish denied, backend-signed connection and subscription JWTs with separate HMAC secrets and a
5-minute TTL, the browser never holding the API key.

## 1. Channels (D12, R6)

| Channel | Durable events | Ephemeral | Who may subscribe (token issued only after this check; [tenant-security.md](tenant-security.md) section 5) |
|---|---|---|---|
| `chat:conversation:<conversationUuid>` | `message.created`, `conversation.updated` | `typing` | the conversation's contact; any agent of the conversation's tenant |
| `chat:inbox:<tenantId>:<inboxUuid>` | `message.created`, `conversation.created`, `conversation.updated` | none | agents of `tenantId`, and only when the inbox belongs to that tenant |

The conversation channel has no tenant segment: conversation UUIDs are globally unique and a subscription token is
bound to the exact channel after the tenant and participant check, so a prefix would add no isolation (R6).
`chat:inbox` (the demo's global channel) is removed with the demo in Plan 06. `Channels` (conversation module root)
builds and parses both forms and rejects anything else (`ChannelsTest`).

## 2. Envelope

Every event, durable or ephemeral, is one JSON object (`EventEnvelope`, schema `event-envelope.v1`):

| Field | Type | Meaning |
|---|---|---|
| `eventId` | UUID | outbox `event_id` for durable events (identical on every redelivery); a random UUID for `typing` |
| `eventType` | string | `message.created`, `conversation.created`, `conversation.updated`, `typing` |
| `schemaVersion` | int | 1 |
| `tenantId` | string | tenant of the conversation |
| `conversationId` | UUID, nullable | the conversation (always set for the four MVP types) |
| `occurredAt` | ISO-8601 | server `Clock` time when the transaction appended the event (before its commit) |
| `data` | object | per type, below |

Centrifugo's own publication offset and epoch are never part of the application contract (master spec 9.3).

## 3. Event types

| `eventType` | Channels | Durable (outbox) | `data` (R14) | Counter carried |
|---|---|---|---|---|
| `message.created` | conversation + inbox | yes | `{message: MessageView}` | `data.message.sequence` (R2) |
| `conversation.created` | inbox | yes | `{conversation: ConversationView}` without `unreadCount` and `agentOnline`, and with `assignment` reduced to `state` and `agentId` (`decisionId`, `reason`, `explanation` null; R-P3) | `data.conversation.version` (R3) |
| `conversation.updated` | conversation + inbox | yes | `{conversation: …same reduced view…, change}` | `data.conversation.version` |
| `typing` | conversation | no (direct publish) | `{participantType, participantId, typing}` | none |

Event payloads never carry the assignment's decision id, reason or explanation, because the conversation channel is
shared with the visitor and those fields describe internal routing (R-P3). The dashboard reads them with
`GET /api/v1/agent/conversations/{id}` when an event changes the conversation it has open.

`change` of `conversation.updated` (D12) and its source ([domain-model.md](domain-model.md) sections 5.3 and 6):

| `change` | Emitted by |
|---|---|
| `RESOLVED` | T1 resolve |
| `REOPENED` | T2, T3 reopen; T6, T7 contact message into `PENDING` / `RESOLVED` |
| `PENDING` | T4 `/pending` |
| `ARCHIVED` | T5 `/archive` (not the purge) |
| `UNARCHIVED` | T8 `/unarchive` |
| `ASSIGNED` | assignment projection into `ASSIGNED` (worker or `/assign`) |
| `ASSIGNMENT_QUEUED` | projection entering `QUEUED` |
| `ASSIGNMENT_FAILED` | projection into `FAILED` |

Contracts: JSON Schemas in `libs/contracts/json-schema/chat/` (`event-envelope.v1`, `message.v1`,
`message-created.v1`, `conversation.v1`, `conversation-created.v1`, `conversation-updated.v1`, `typing.v1`) and
`libs/contracts/asyncapi/chat-realtime.yaml`, written in Plan 03 T5 and checked by `EventEnvelopeContractTest` and the
live contract tests of Plan 06 T4. Producers and producer-side schemas are strict (`additionalProperties: false`);
consumers ignore unknown fields so a schema version can add a field without breaking clients (R15).

Message text in events: `message.created` carries the body and `conversation.*` carry `lastMessagePreview` (up to 120
characters of a body). Only the subscribers allowed by section 1 receive them; outbox rows holding them are deleted by
the purge (D16), and Centrifugo history holds them for at most `history_ttl` (300 s).

## 4. Outbox publisher (D6)

`OutboxAppender.append(…)` writes one `outbox_events` row inside the business transaction (it throws
`IllegalStateException` outside one). `OutboxPublisher` (outbox module, scheduled every `poll-delay`; tests call
`runOnce()`) relays rows to Centrifugo:

| # | Step | Mechanism |
|---|---|---|
| 1 | **Claim** a bounded batch in its own short transaction, then commit | `UPDATE outbox_events SET locked_by = :worker, locked_until = :now + :lease, attempts = attempts + 1 WHERE event_id IN (SELECT event_id FROM outbox_events WHERE status = 'PENDING' AND next_attempt_at <= :now AND (locked_until IS NULL OR locked_until < :now) ORDER BY next_attempt_at, created_at LIMIT :batch FOR UPDATE SKIP LOCKED) RETURNING …` |
| 2 | **Publish** each row outside any transaction | `RealtimePublisher.broadcast(channels, payload, idempotencyKey = eventId)` → `POST /api/broadcast` `{channels, data, idempotency_key}` with `X-API-Key`; 2 s timeout. Success needs HTTP 200, no top-level `error` and no `error` in any `result.responses[i]`; Centrifugo's 200-with-error is a failure (`OutboxPublisherIT.centrifugoErrorObjectIsAFailure`). |
| 3a | **Mark published** | `status = 'PUBLISHED'`, `published_at = now`, lock cleared, `WHERE event_id = :id AND locked_by = :worker` |
| 3b | **Mark failed**: retry with backoff | `next_attempt_at = Backoff.next(now, attempts − 1, base, max, rng)`. `Backoff.next(now, n, base, max, rng) = now + min(base × 2^n ± 20 % jitter, max)` takes a 0-based retry index `n`, so the first retry waits about `base` (R-P15; jitter before the cap, overview §7 J); `last_error` = exception class name or HTTP status, never payload text; lock cleared; same `locked_by` guard |
| 3c | **Park** after the retry budget | a failure with claimed `attempts >= max-attempts` sets `status = 'PARKED'`; parked rows are never dropped and never retried automatically |
| 4 | **Recover crashed workers** | a row whose `locked_until` passed is claimable again; the crash used up one attempt. A mark by a worker that lost its lease updates 0 rows and is ignored (`OutboxPublisherIT.lostLeaseMarkIsIgnored`). |
| 5 | **Replay** (operator) | `POST /api/v1/admin/outbox/{eventId}/replay`: `PARKED` → `PENDING`, `attempts = 0`, `next_attempt_at = now`; logged at INFO with `eventId` (R16). `GET /api/v1/admin/outbox?status=&limit=` lists rows. Local-dev only, `X-Admin-Token`. |

Configuration (overview §5): `app.outbox.enabled=true` (gates only the scheduled tick), `poll-delay=500ms`,
`batch-size=100`, `lease=30s`, `max-attempts=10`, `base-backoff=1s`, `max-backoff=60s`. With these values a
continuously failing row parks about 4 minutes after its first attempt ([system-overview.md](system-overview.md)
section 4).

Observability (master spec 9.2 step 7, 15): gauges `outbox.pending.count`, `outbox.parked.count`,
`outbox.pending.oldest_age_seconds`; counter `outbox.publish.attempts{outcome=success|failure|parked}`; timer
`outbox.publish.duration`; `chat.realtime.publications{outcome, kind=publish|broadcast}`. Every publisher log line
carries `eventId`, `eventType`, `attempts` and the row's `trace_id` (from the API request), never the payload.

Where duplicates come from, so clients must expect them:
- a worker publishes and dies before step 3a: the next claim republishes the same `eventId`
  (`OutboxPublisherIT.workerCrashAfterPublishBeforeMarkRepublishes`);
- a lease expires during a slow publish and a second worker republishes;
- Centrifugo recovery replays events the client also fetches through the API;
- an agent subscribed to both channels receives each `message.created` and `conversation.updated` once per channel.

`idempotency_key = eventId` lets Centrifugo suppress some republishes within its own deduplication window (Plan 03
records about 5 minutes for the memory engine); the platform does not rely on it.

Order: rows are claimed by `(next_attempt_at, created_at)`, but a retried row, two workers or two channels can deliver
a later event first. Clients therefore order by `sequence` and `version`, never by arrival.

## 5. Client synchronisation algorithm (master spec 10.3)

State per open conversation (`createConversationSync` in `libs/chat-client`, Plan 06 T1):
- `lastContiguous` (called `lastSequence()` in `sync.ts`): highest `sequence` such that every message up to it, from the
  oldest loaded page on, has been applied;
- `knownVersion`: `version` of the last applied `ConversationView`;
- a bounded set of seen `eventId`s (500 entries) per view: the dashboard's inbox list and its open thread keep separate
  sets, so one event delivered on both channels is applied once to each view;
- a buffer of events received while a reconcile is running, and a `caughtUp` flag.

**Bootstrap** (subscribe first, then fetch; zulip.md SI-1):

1. **Session.** Widget: `ensureSession` refreshes the stored token with `POST /api/v1/widget/sessions`, or starts a new
   session. Dashboard: `POST /api/v1/agent/local-login` (local-dev).
2. **Conversation.** Widget: `POST /api/v1/widget/conversations` (start or resume) → `ConversationView` (`id`,
   `version`, `lastSequence`). Dashboard: from the inbox list.
3. **Connect** to Centrifugo; `getToken` calls `GET /api/v1/{widget|agent}/realtime/connection-token`.
4. **Subscribe** to `chat:conversation:<id>` (the dashboard also to each `chat:inbox:<tenantId>:<inboxId>`);
   `getToken` calls `…/realtime/subscription-token?channel=`. From here on, events are buffered until caught up.
5. **On every `subscribed`** (the first one and each after a reconnect): run **reconcile** (below). Recovery is only an
   optimisation; the reconcile runs even after a successful recovery (R5).

**Reconcile** (one in-flight run per conversation; concurrent calls share it):

1. If `lastContiguous = 0`: load the newest page (no cursor, `limit` 50); `lastContiguous` = its highest `sequence`.
   Older pages load on demand with `before=` (`load-older`).
2. Otherwise fetch `?after=<lastContiguous>&limit=200`, merge by `sequence`, advance `lastContiguous`; repeat while
   `hasMore`, **at most 5 pages**. If `hasMore` is still true after 5 pages, do a **full reload** (R5, R-P5).
3. Fetch the conversation view (`GET …/conversations/{id}`) and set `knownVersion` from it: conversation events have no
   catch-up endpoint, so the view is how missed status, archive and assignment changes are reconciled (master spec
   10.4 step 3: "cursor/version"; R-P5).
4. Apply the buffered events by the rules below; the client is caught up when `lastContiguous` is at least the highest
   `sequence` seen on the channel.

**Event rules:**

| Event | Rule |
|---|---|
| any | `eventId` already seen → drop. Unknown `eventType` or unknown fields → ignore (R15). |
| `message.created` with `sequence = s` | `s ≤ lastContiguous` → drop (duplicate). `s = lastContiguous + 1` → apply, then advance (SI-6: never before the apply succeeded). `s > lastContiguous + 1` → keep it buffered and reconcile (gap). An own message that arrives before its `POST` response is matched to the pending bubble by `clientMessageId` and rendered once (`liveEventBeforeSendResponseRendersOnce`). |
| `conversation.created` / `conversation.updated` with `version = v` | `v ≤ knownVersion` → drop. `v = knownVersion + 1` while caught up → apply the view (keep the per-caller `unreadCount` and `agentOnline` from the last `GET`). `v > knownVersion + 1`, or any conversation event while not caught up → refetch the conversation view (R3, R-P5). The event's `assignment` holds only `state` and `agentId` (R-P3): the dashboard refetches the agent view of the conversation it has open to show reason and explanation. |
| `typing` | apply to the typing indicator with a 5 s receiver timeout; never buffered, never reconciled, never counted. |
| an apply that throws | full reload of that conversation (SI-6). |

**Full reload:** discard the local copy of the conversation's server state (keep unsent drafts and pending bubbles),
load the newest page and the conversation view, set `lastContiguous` and `knownVersion` from them, never rebuild from
buffered or earlier event payloads (SI-4 case b). Triggers: more than 5 catch-up pages, a gap the catch-up does not
close (the first returned item is not `lastContiguous + 1`), a failed apply, an API head (`ConversationView.lastSequence`)
below `lastContiguous`, and a subscription refused or ended by the server.

**Inbox channel** (dashboard): it has no sequence. On every `subscribed` the dashboard refetches the inbox list. A
`conversation.*` event updates the list item under the version rule; a `message.created` event updates the item's
preview, `lastSequence` and unread badge and never touches the open thread's messages
(`inbox-list.spec.tsx: inboxEventDoesNotLeakIntoOpenThread`).

**Sending** (`pending.ts`): a send gets `clientMessageId = crypto.randomUUID()` and a bubble in state `pending`; success
marks it `sent`; a failure or timeout (8 s) marks it `failed` with Retry, which resends with the same
`clientMessageId` (D5). Unsent bubbles are never shown as accepted.

## 6. Reconnect (master spec 10.4)

| Spec step | Platform behaviour |
|---|---|
| 1 Re-establish authenticated connection and subscriptions | The `centrifuge` SDK reconnects with backoff (**existing** defaults 500 ms to 20 s, workspace-inventory.md 4.5) and calls the `getToken` callbacks for fresh 5-minute tokens. A token request answered 401 means the session token expired: the widget refreshes it once with `POST /widget/sessions` and the stored token (`verifyForRefresh` keeps the contact; `session.spec.ts: expiredTokenIsRefreshedOnce`), the dashboard logs in again. A 403 means the caller lost access: stop and show the error. |
| 2 Attempt Centrifugo short-history recovery | `force_recovery: true` makes the SDK ask for the missed publications of the last `history_ttl` (300 s, at most 100 per channel). Replayed events go through the event rules of section 5. Recovery failed only when the subscribed context has `wasRecovering = true && recovered = false` (R4). |
| 3 Reconcile with the durable API | Always, on every `subscribed`: `?after=<lastContiguous>` (at most 5 pages) plus the conversation view (R5). |
| 4 Deduplicate | by `eventId`, message `id` and `sequence` (section 7). |
| 5 Bounded full refresh | the full reload of section 5 when continuity cannot be proven, including after a failed recovery whose gap needs more than 5 pages. |

Centrifugo history is configured in the `chat` namespace of `infra/centrifugo/config.json` as `history_size: 100`,
`history_ttl: "300s"`, `force_recovery: true` (D12; key names verified against the Centrifugo channel documentation on
27 Oct 2026, overview §7 K; Plan 03 T3 re-checks them with `centrifugo checkconfig` on the pinned image). It is a
short-gap optimisation only. **Existing:** the engine is the in-memory default, single node
(workspace-inventory.md 4.3), so history is lost when Centrifugo restarts; the API reconcile covers that.

## 7. Duplicate handling

| Duplicate | Detected by | Result |
|---|---|---|
| Republished outbox event (same `eventId`) | seen-`eventId` set | dropped |
| Recovery replay overlapping the API catch-up | `sequence ≤ lastContiguous`; message `id` merge (`mergeMessages`) | dropped |
| Same event on the conversation and the inbox channel | seen-`eventId` set per view (inbox list, open thread) | applied once per view |
| Own message event before the `POST` response | `clientMessageId` on the pending bubble | one bubble |
| Client retry of a send | server: idempotency key (D5) | one row, 200 replay |
| Stale or repeated conversation event | `version ≤ knownVersion` | dropped |

## 8. Typing (master spec 11.3)

- `POST /api/v1/{widget|agent}/conversations/{id}/typing` with `{typing: boolean}` → 202. `TypingService` checks the
  participant (D14), applies the rate limit of 1 per 2 s per `tenant:principal:conversation` (429 `RATE_LIMITED`), then
  publishes directly with `RealtimePublisher.publish` to the conversation channel only. No outbox row, no database
  write, no sequence, no version (D6, R3). A failed publish (Centrifugo down) is logged at debug and still answers 202.
- Clients never publish to Centrifugo (client publish stays denied). Emitter: at most one `typing: true` per 2 s while
  typing, `typing: false` after 3 s idle and on send. Receiver: clears after 5 s without a new event, so a lost stop
  event cannot leave the indicator on.
- Losing typing events changes no state (SI-10). Tenant B cannot inject typing into tenant A's conversation
  (`TypingIT.crossTenantIs403`).

## 9. Synchronisation invariants from the Zulip study

The invariants are defined in [zulip.md](../research/evidence/zulip.md) section 16 with their evidence. This table
restates each for this platform, applies the Plan 01 rulings where the note differs
([reference-comparison.md](../research/reference-comparison.md) section 2), and names the tests that cover it. What is
not copied from Zulip: its per-client event queues, long polling, Tornado, server-side event replay and compaction.

| Id | Rule for this platform | Adapted from the note | Enforced in | Tests (Plans 02 to 07) |
|---|---|---|---|---|
| SI-1 | Subscribe before requesting history; apply message events above the snapshot head, drop those at or below it. | "events" → "message events" (R2) | `sync.ts` bootstrap | `sync.spec.ts: eventDuringInitialLoadIsAppliedOnce`; E2E `messageDuringBootstrapIsNotLost` |
| SI-2 | The database assigns a stable, monotonic, gap-free `sequence` per conversation under the row lock. | as written | send transaction step 5 | `MessageConcurrencyIT.distinctConcurrentSendsHaveContiguousSequences`, `MessageWriteIT.rollbackLeavesNoPartialState`, `Invariants.assertAll` |
| SI-3 | Track `lastContiguous`; drop at or below, apply the next, fetch `?after=` on a gap. | as written | `sync.ts` | `sync.spec.ts: ignoresStaleSequences`, `gapTriggersCatchUp`, `appliesEventsInOrder` |
| SI-4 | (a) Delta catch-up when continuity can be restored; (b) full reload of the newest page when it cannot be proven; never rebuild from client memory. | (b) trigger "recovery failed" = `wasRecovering && !recovered` (R4); (a) capped at 5 pages, then (b) (R5) | `sync.ts` reconcile | `sync.spec.ts: failedRecoveryTriggersCatchUp`, `catchUpPagesWhileHasMore`, `emptyStartLoadsNewestPageOnly`; E2E `centrifugoRestartConverges`, `refreshDuringActiveConversation` |
| SI-5 | Durable events carry a stable `eventId`; message events carry `sequence`, conversation events `version`; applying twice equals applying once. | "every event carries sequence" → message events only (R2, R3) | outbox `event_id`; `sync.ts` | `sync.spec.ts: ignoresDuplicateEventIds`; `OutboxPublisherIT.workerCrashAfterPublishBeforeMarkRepublishes`; widget `liveEventBeforeSendResponseRendersOnce` |
| SI-6 | Advance `lastContiguous` only after a successful apply; a failed apply triggers a full reload. | as written | `sync.ts` | none named in Plan 06 (gap, see feature spec section 8) |
| SI-7 | An event is published if and only if its transaction committed, and a committed event is eventually published or parked for replay. | "eventually" bounded by parking (section 4) | outbox appender and publisher | `MessageWriteIT.rollbackLeavesNoPartialState`; `OutboxPublisherIT.centrifugoRestartDrainsBacklog`, `workerCrashAfterClaimIsRecoveredAfterLease`, `workerCrashAfterPublishBeforeMarkRepublishes`, `poisonEventIsParkedAfterMaxAttempts` |
| SI-8 | Events buffered during a history fetch are applied after it when contiguous from the head; a gap falls back to SI-3. | as written | `sync.ts` | `sync.spec.ts: eventDuringInitialLoadIsAppliedOnce` |
| SI-9 | A bad cursor never changes or discards server data: `after` beyond the head returns an empty page; the read cursor is monotonic and clamped; an API head below `lastContiguous` makes the client reload. | the head comes from `ConversationView.lastSequence` (no head in `MessagePage`), and `PUT …/read` answers 204 (overview §5) | `HistoryService`, `ReadCursorService`, `sync.ts` | `HistoryIT.pagingBeyondRangeAndOversizedLimitAreBounded`; `ReadCursorIT.advanceIsMonotonic`, `cursorCannotExceedLastSequence`; client side: none named (gap) |
| SI-10 | Durable and ephemeral events are separate classes; losing typing changes nothing. | as written | `TypingService`; `typing.ts` | `TypingIT.typingIsRateLimitedAndNeverPersisted`, `centrifugoDownStillReturns202`; `typing.spec.ts: receiverClearsAfterTimeout`; E2E `typingIndicatorTimesOutWhenStopIsLost` |
| SI-11 | The writer decides the audience; a subscription is authorized per exact channel after the tenant and participant check. | "channel name contains the tenant" → only the inbox channel does; the conversation channel relies on the token (R6) | `ChannelAuthorizer`, token endpoints | `RealtimeTokenIT` (all cases); contract `subscriptionTokenForAnotherChannelIsRejected`; E2E `nonParticipantCannotLoadHistoryOrSubscribe` |
| SI-12 | Snapshot plus message events equals a fresh snapshot, for events before, after and straddling the snapshot, with duplicates. | "every event type" → message events; conversation events reconcile by refetch (R3) | `sync.ts` | `sync.spec.ts: appliesEventsInOrder`, `eventDuringInitialLoadIsAppliedOnce` (the three-interleaving property test is not named in Plan 06: gap) |
| SI-13 | History pages are bounded and internally consistent. | over-limit requests are clamped to 200 instead of rejected (Plan 02); one `SELECT` gives the snapshot (R11) | `HistoryService` | `HistoryIT.pageBeforeReturnsOlderAscendingWithHasMore`, `pagingBeyondRangeAndOversizedLimitAreBounded` |
