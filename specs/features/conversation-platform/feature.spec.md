# Feature: Multi-tenant conversation platform

- **Status:** In review (design, Plan 01); implemented by Plans 02 to 07
- **Owner:** brewflock
- **Date:** 2026-10-27
- **Related ADRs:** [ADR-0002](../../decisions/ADR-0002-modular-monolith.md), [ADR-0003](../../decisions/ADR-0003-dedicated-flyway-runner.md),
  [ADR-0004](../../decisions/ADR-0004-centrifugo-realtime.md), [ADR-0005](../../decisions/ADR-0005-conversation-platform-modules.md),
  [ADR-0006](../../decisions/ADR-0006-message-write-and-ordering.md), [ADR-0007](../../decisions/ADR-0007-transactional-outbox-and-centrifugo.md),
  [ADR-0008](../../decisions/ADR-0008-tenant-isolation-and-local-identity.md), [ADR-0009](../../decisions/ADR-0009-allocation-service-integration.md)
- **Design:** [system overview](../../../docs/architecture/system-overview.md) and the documents it indexes; plan set
  [00-overview](../../../docs/superpowers/plans/2026-10-27-conversation-platform-00-overview.md) (decisions `D1` to
  `D20`, glossary §5; §8 there lists the rulings that changed the plans); rulings `R1` to `R16` and `R-P1` to `R-P17` in
  system-overview.md section 7.

## 1. Problem and desired outcome

Today the platform is a local chat proof of concept: one hard-coded visitor (`userone`) and agent (`agentbob`), no
tenants, no authentication, no lifecycle, no idempotent send, publish-after-commit with no retry, history without
pagination and no allocation ([workspace-inventory.md](../../../docs/research/workspace-inventory.md) section 8.2).

When this feature ships, a developer starts the whole platform with one documented Compose command, and a website
visitor and an agent in separate browser sessions can exchange messages in real time across tenants that cannot see
each other; refresh or disconnect without losing durable history; see typing and agent presence; have conversations
assigned by the existing allocation service; resolve, archive, unarchive and reopen conversations; read paginated
history with unread counts; and an operator can inspect and replay the outbox and run a dry-run and bounded purge. All
of it is proven by tests against real PostgreSQL and Centrifugo, and every load figure is measured, not inferred.

## 2. Actors, scope and non-goals

- **Actors:** visitor (contact, widget), agent (dashboard), admin/operator (local-dev admin API), allocation service
  (assignment authority), system (outbox publisher, assignment worker, retention job, seeders).
- **In scope (MVP):** tenancy and local identity (D7); conversations with `OPEN` / `PENDING` / `RESOLVED` and an
  independent archive marker (D10); idempotent, strictly ordered text messages (D4, D5); history and read cursors
  (D13); transactional outbox to Centrifugo (D6); channel authorization (D14); typing (ephemeral) and presence through
  the allocation service (D9); allocation integration with projection and worker (D8); retention policy, dry run and
  bounded purge (D16); rate limits (D17); widget and dashboard on the new APIs (D18); demo module removal (Plan 06);
  Compose with the allocation service (Plan 04); race, fault, E2E and load verification (D19).
- **Non-goals:** attachments and file upload (NEXT; schema only, D15); message edit and delete endpoints (NEXT);
  snooze, labels/tags, teams, bots, canned responses, SLA, reactions, search, read receipts, per-tenant display numbers
  (DEFER); production authentication (OIDC, HMAC-verified widget identity: replacements documented, not built);
  multi-node Centrifugo, row-level security, scheduled purge, Redis/Kafka/NATS or any new infrastructure; a claim of
  10,000-client capacity (a design target that stays unvalidated until measured; Plan 07 measures it in tiers).

## 3. User stories and acceptance criteria

Each AC names the master spec 18 item it proves (`18-F1` = the first "Functional correctness" bullet, `18-D` = "Data
integrity and security", `18-L` = "Local platform").

- **US-1** As a visitor I want to start or resume a conversation so that I reach the business without losing context.
  - AC-1.1 Given a valid `widgetKey`, when the widget opens, then `POST /widget/sessions` returns 201 with a contact
    session token whose tenant and inbox are the key's (18-F1).
  - AC-1.2 When the visitor starts, then `POST /widget/conversations` returns 201 with a new conversation
    (`assignment.state = REQUESTED`) or 200 with the latest non-archived one; concurrent starts create one (D11).
  - AC-1.3 Given an expired stored token, when the widget reopens, then the session is refreshed for the same contact
    and history reloads.
  - AC-1.4 An unknown `widgetKey` is 404; the 11th session request in a minute for one key and IP is 429.
- **US-2** As a tenant I want new conversations assigned by the existing allocation service so that work is routed by
  its policies.
  - AC-2.1 Given agent `agentbob` is `ONLINE`, when a conversation starts, then within 15 s it is `ASSIGNED` to
    `agentbob` in the platform and in the allocation service, and the dashboard receives `conversation.updated` with
    `change = ASSIGNED` (18-F2).
  - AC-2.2 Given no available agent, then the conversation shows `QUEUED` with the reason, and is assigned without
    platform action once an agent becomes available.
  - AC-2.3 Given the allocation service is down, then the conversation stays `REQUESTED`, chat continues, and assignment
    completes after recovery.
  - AC-2.4 An agent can claim, auto-assign or reassign with `POST …/assign`; a second claim is 409
    `ALLOCATION_REJECTED` and the first assignment stays.
  - AC-2.5 Resolving releases the agent's capacity in the allocation service.
- **US-3** As an agent I want to see waiting conversations and reply so that visitors get answers.
  - AC-3.1 A new conversation appears in the `queue` view within 10 s (18-F3).
  - AC-3.2 Any agent of the tenant can reply to any non-archived, non-resolved conversation; the reply is 201 and adds an
    `AGENT` participant (D14).
- **US-4** As a participant I want messages stored durably and delivered live so that nothing is lost.
  - AC-4.1 A send answers 201 only after the message, the conversation update and the `message.created` outbox row
    committed together; a failure leaves none of them (18-F4, 18-D2).
  - AC-4.2 Both UIs show the message through Centrifugo, with the outbox row `PUBLISHED` within 10 s (18-F4).
  - AC-4.3 Given Centrifugo is down, sends still answer 201; after Centrifugo returns, the outbox drains and both UIs
    converge (18-D3).
- **US-5** As a participant I want refresh and reconnect to restore complete history without duplicates.
  - AC-5.1 Reloading the widget mid-conversation shows the complete history and live updates (18-F5).
  - AC-5.2 A message published while the client loads history appears once, with no duplicate (18-F5).
  - AC-5.3 The same event delivered twice, or a message received both by event and by catch-up, renders once (18-D4).
  - AC-5.4 After a failed Centrifugo recovery the client reconciles from the API; after more than 5 catch-up pages it
    loads the newest page instead (R4, R5).
- **US-6** As a participant I want typing and presence signals without polluting history.
  - AC-6.1 Typing shows the other side's indicator, answers 202, writes no row and is limited to 1 per 2 s; the
    indicator clears when the stop signal is lost (18-F6).
  - AC-6.2 The agent's `ONLINE`/`AWAY`/`OFFLINE` choice reaches the allocation service; the widget shows the agent
    online/offline; a closed dashboard becomes offline after the 60 s heartbeat TTL (18-F6).
- **US-7** As an agent I want resolve, pending, archive, unarchive and reopen to follow one documented state machine.
  - AC-7.1 Every status × archive × command case answers as in [domain-model.md](../../../docs/architecture/domain-model.md)
    section 5.2 (18-F7).
  - AC-7.2 A repeated command is a 200 no-op; a stale `expectedVersion` is 409 `STALE_VERSION`; two conflicting
    commands with one version give one 200 and one 409 (18-D6).
  - AC-7.3 A contact message into `RESOLVED` reopens it (`REOPENED_BY_CONTACT`); into an archived conversation it is 409
    `CONVERSATION_ARCHIVED` and the widget offers a new conversation.
  - AC-7.4 An archived conversation leaves the default list and appears with `archived=true`; unarchive or reopen that
    would create a second active conversation for the contact is 409 `CONVERSATION_ACTIVE_EXISTS`.
  - AC-7.5 Archiving a conversation releases its agent's capacity in the allocation service; unarchiving an `OPEN` or
    `PENDING` conversation requests assignment again (R-P13).
- **US-8** As a participant I want paginated history and unread counts.
  - AC-8.1 `before=` pages walk the whole history in pages of 50 without gaps or repeats; `after=` returns the newer
    messages; `limit` is clamped to 200 (18-F8).
  - AC-8.2 Unread = `last_sequence − cursor`; the cursor never moves back or past the head; own messages are not unread
    (18-F8).
- **US-9** As an operator I want a retention dry run and a bounded purge.
  - AC-9.1 A dry run reports counts and changes nothing (18-F9).
  - AC-9.2 A purge deletes only eligible conversations' messages, attachment rows, cursors and outbox rows in bounded
    batches, archives them, keeps conversation and state-event rows, and records the run; a tenant on legal hold is
    untouched (18-F9).
- **US-10** As a tenant I want my data isolated and consistent under concurrency.
  - AC-10.1 Retrying a send with the same `clientMessageId` and body yields one row; a different body is 409 (18-D1).
  - AC-10.2 No API read or write and no subscription token crosses tenants, and a contact cannot read another contact's
    conversation (18-D5).
  - AC-10.3 Concurrent sends to one conversation get contiguous sequences; a send racing a resolve ends in one of the
    two documented outcomes with no lost message (18-D6).
- **US-11** As an operator I want to see and repair delivery and dependency problems.
  - AC-11.1 `GET /api/v1/admin/outbox?status=PARKED` lists parked events and replay republishes them; admin routes do
    not exist when `app.local-dev.enabled=false`.
  - AC-11.2 `GET /api/v1/status` reports database, Centrifugo and allocation; metrics expose outbox backlog, send
    outcomes, allocation outcomes and rate-limit rejections (master spec 15).
- **US-12** As a developer I want one command to run everything locally.
  - AC-12.1 From a clean checkout the documented command brings every container to healthy and applies V1 to V3 to an
    empty database (18-L1, 18-L2).
  - AC-12.2 No reference repository is modified and no secret is committed or reachable by the browser (18-L3, 18-L4).

## 4. Data model and migration impact

- New tables in `database/flywayMigration/sql/V3__conversation_platform.sql` (Plan 02 T1), every one with `tenant_id`
  except `tenants` itself: `tenants`, `inboxes`, `contacts`, `agents`, `conversations`, `conversation_participants`,
  `messages`, `message_attachments`, `conversation_read_cursors`, `conversation_state_events`, `outbox_events`,
  `retention_policies`, `purge_runs`; `conversations` includes `assignment_setup_failures` (R-P2), and the nullable
  columns are exactly those domain-model.md marks (R-P7). Columns, keys, checks and indexes: [domain-model.md](../../../docs/architecture/domain-model.md)
  section 3.
- Expand/contract: V3 only adds; V1 and V2 stay untouched, so the demo keeps working until Plan 06 removes its code.
  V2's `chat_conversations` and `chat_messages` are dropped by a later migration (`V4` or later) only after Plan 06.
- No backfill: demo data in the V2 tables is not migrated.
- Retention: per-tenant `resolved_retention_days` (seed 30) and `legal_hold`; purge is manual (admin) in the MVP.

## 5. API / event contract changes

OpenAPI in `libs/contracts/openapi/platform-api.yaml` (bearer `bearerAuth` and `AdminToken` schemes; `Problem` gains
required `code` and optional `traceId`, `reasonCodes`). All endpoints of overview §5:

| Endpoint | Auth | Request → response | Plan |
|---|---|---|---|
| `POST /api/v1/widget/sessions` | public | `{widgetKey, externalId?, displayName?, sessionToken?}` → `{sessionToken, expiresAt, tenantId, inbox:{id,name}, contact:{id,displayName}}` | 02 |
| `POST /api/v1/widget/conversations` | contact | start or resume → `ConversationView`, 201 created / 200 resumed | 02 |
| `GET /api/v1/widget/conversations/{id}` | contact | → `ConversationView` (with `agentOnline`; `assignment` shows only `state` and `agentId`, R-P3) | 02, 04 |
| `GET /api/v1/widget/conversations/{id}/messages?before=&after=&limit=` | contact | → `MessagePage` | 02 |
| `POST /api/v1/widget/conversations/{id}/messages` | contact | `SendMessageRequest` → `MessageView`, 201 / 200 replay | 02 |
| `POST /api/v1/widget/conversations/{id}/typing` | contact | `{typing}` → 202 | 03 |
| `PUT /api/v1/widget/conversations/{id}/read` | contact | `{lastReadSequence}` → 204 | 02 |
| `GET /api/v1/widget/realtime/connection-token` | contact | → `{token, expiresAt}` | 03 |
| `GET /api/v1/widget/realtime/subscription-token?channel=` | contact | → `{token, expiresAt}` | 03 |
| `POST /api/v1/agent/local-login` | public, local-dev only | `{agentId, tenantId?}` → session | 02 |
| `GET /api/v1/agent/me` | agent | → `{agentId, tenantId, displayName, availability, lastHeartbeatAt}` | 04 |
| `PUT /api/v1/agent/presence` | agent | `{availability}` → same shape | 04 |
| `GET /api/v1/agent/inboxes` | agent | → inboxes of the tenant | 02 |
| `GET /api/v1/agent/inboxes/{inboxId}/conversations?view=&archived=&cursor=&limit=` | agent | → `{items: ConversationView[], nextCursor}` | 02 |
| `GET /api/v1/agent/conversations/{id}` | agent | → `ConversationView` | 02 |
| `GET /api/v1/agent/conversations/{id}/messages` | agent | → `MessagePage` | 02 |
| `POST /api/v1/agent/conversations/{id}/messages` | agent | `SendMessageRequest` → `MessageView` | 02 |
| `POST /api/v1/agent/conversations/{id}/typing` | agent | `{typing}` → 202 | 03 |
| `PUT /api/v1/agent/conversations/{id}/read` | agent | `{lastReadSequence}` → 204 | 02 |
| `POST /api/v1/agent/conversations/{id}/assign` | agent, `Idempotency-Key` | `AssignRequest` → `ConversationView` (501 `NOT_IMPLEMENTED` until Plan 04) | 02, 04 |
| `POST /api/v1/agent/conversations/{id}/resolve`, `/reopen`, `/pending`, `/archive`, `/unarchive` | agent | `CommandRequest` → `ConversationView` | 02 |
| `GET /api/v1/agent/realtime/connection-token` | agent | → `{token, expiresAt}` | 03 |
| `GET /api/v1/agent/realtime/subscription-token?channel=` | agent | → `{token, expiresAt}` | 03 |
| `GET /api/v1/admin/outbox?status=&limit=` | `X-Admin-Token`, local-dev | → `OutboxEventView[]` | 03 |
| `POST /api/v1/admin/outbox/{eventId}/replay` | admin | → `OutboxEventView` / 404 | 03 |
| `POST /api/v1/admin/retention/purge` | admin | `PurgeRequest` → `PurgeRunView` | 05 |
| `GET /api/v1/admin/retention/runs/{id}` | admin | → `PurgeRunView` / 404 | 05 |
| `PUT /api/v1/admin/retention/policies/{tenantId}` | admin | `{resolvedRetentionDays, legalHold}` → 200 | 05 |
| `POST /api/v1/admin/tenants` | admin | `{id, name, widgetKey, queueId, agents:[SeedAgent]}` → 201 / 200 | 05 |
| `GET /api/v1/status` (existing) | public | gains `allocation` | 04 |
| `GET /api/v1/bootstrap`, `/actuator/health`, `/actuator/prometheus` (local profile) (existing) | public | unchanged | |
| `/api/v1/demo/*` (existing) | none | removed | 06 |

- **Problem codes:** `UNAUTHENTICATED` 401, `FORBIDDEN` 403, `NOT_FOUND` 404, `VALIDATION_FAILED` 400, `RATE_LIMITED`
  429, `CLIENT_MESSAGE_ID_REUSED`, `CONVERSATION_RESOLVED`, `CONVERSATION_ARCHIVED`, `INVALID_TRANSITION`,
  `STALE_VERSION`, `CONVERSATION_ACTIVE_EXISTS`, `ALLOCATION_REJECTED` 409, `ALLOCATION_UNAVAILABLE`,
  `DATABASE_UNAVAILABLE` 503, `NOT_IMPLEMENTED` 501 (Plan 02 placeholder); framework errors get status-derived codes.
  Every problem carries `traceId`.
- **Events** (Centrifugo, at least once): channels `chat:conversation:<uuid>` and `chat:inbox:<tenantId>:<inboxUuid>`;
  envelope `{eventId, eventType, schemaVersion, tenantId, conversationId, occurredAt, data}`; types `message.created`,
  `conversation.created`, `conversation.updated` (`change`), `typing`. Conversation event payloads carry the assignment's
  `state` and `agentId` only (R-P3); agents read routing details with `GET /api/v1/agent/conversations/{id}`. JSON Schemas `libs/contracts/json-schema/chat/*.v1`
  and AsyncAPI `libs/contracts/asyncapi/chat-realtime.yaml` (Plan 03 T5); details in
  [realtime-and-recovery.md](../../../docs/architecture/realtime-and-recovery.md).
- **Versioning / deprecation:** event schemas are versioned by file (`.v1`) and `schemaVersion`; consumers ignore
  unknown fields, producers are strict (R15). The demo paths, demo schemas and `chat:inbox` channel are removed in
  Plan 06 T4, which deletes the only clients using them.

## 6. Tenant authorization and threat considerations

- Tenant and principal come only from verified session tokens (contact or agent) or the local-dev admin token; every
  data statement filters `tenant_id` (exceptions in the next bullet); cross-tenant access is 403 at the API and at
  subscription-token issuance; subscription tokens are bound to one exact channel. Full rules: [tenant-security.md](../../../docs/architecture/tenant-security.md).
- Threats and mitigations (spoofing, cross-tenant leakage, channel hijack, forged events, XSS, abuse, log leakage,
  enumeration, admin misuse, routing details shown to visitors): tenant-security.md section 8. Every data
  statement is tenant-filtered except the authorization probe `tenantAndInboxOf(id)`, statements keyed only by primary
  key inside a transaction after a tenant-filtered lock or lookup of that row, and the worker claims and marks (R-P6
  amended; tenant-security.md section 4). Local shortcuts ("LOCAL POC — NOT REAL AUTHENTICATION") and
  their production replacements: section 9 there.
- Secrets introduced: `APP_SESSION_TOKEN_HMAC_SECRET_KEY`, `APP_LOCAL_ADMIN_TOKEN`, `ALLOCATION_DB_PASSWORD`
  (`__generate__` in `.env.example`); flags `APP_LOCAL_DEV_ENABLED` (default false), `APP_SEED_WIDGET_KEY`.

## 7. UI states and accessibility

- **Widget** (`<chat-platform-widget>`, attributes `api-base-url`, `realtime-url`, `widget-key`, `external-id`,
  `display-name`): loading (session and start); connection badge `connection-state` (Connecting, Live, Reconnecting,
  Offline); empty (`greeting`); messages with `data-message-id`, `data-sequence`, `data-state = pending | sent | failed`
  and a `retry` button on failed ones; `typing-indicator`; `agent-presence` (Online, Offline, Unknown when `agentOnline`
  is null); `conversation-status` (`OPEN`, `PENDING`, `RESOLVED`, "Closed" when archived); `new-conversation` after 409
  `CONVERSATION_ARCHIVED`; `widget-error`, `widget-notice` (including the POC notice); `widget-messages` keeps
  `data-subscribed`.
- **Dashboard** (`/agent`): `queue-tab`, `mine-tab`, all, `archived-toggle`, `inbox-empty`, `inbox-item` with
  `unread-count`, status and assignment state; conversation pane with `load-older`, `agent-messages`, `typing-indicator`,
  `assignment-state`, `assignment-explanation` (read from the agent view, refetched when an event changes the open
  conversation, R-P3), actions `action-assign-me`, `action-auto-assign`, `action-reassign`,
  `action-resolve`, `action-reopen`, `action-pending`, `action-archive`, `action-unarchive` (each sends
  `expectedVersion`; `STALE_VERSION` shows a notice and reloads); `availability-select`, `connection-state`,
  `agent-error`, `agent-notice`, `agent-id` ("Demo agent: agentbob") and the POC banner.
- **Accessibility:** every action is a native button or form control reachable by keyboard; status and presence are
  shown as text, not colour alone; message bodies render as text nodes; colours come from semantic `--cp-*` tokens; the
  message list is exposed as a live log region so new messages are announced politely.

## 8. Test plan and observable success criteria

| Level | Tests (class or file names from Plans 02 to 07) |
|---|---|
| Unit | `SessionTokensTest`, `LocalDevGuardTest` (02); `ConversationLifecycleTest` (42 cases), `OutboxAppenderTest` (02); `BackoffTest`, `ChannelsTest`, `RealtimePublisherTests`, `EventEnvelopeContractTest` (03); `AllocationClientTest`, `AllocationSeederTest` (04); `LogHygieneTest` (05); `ModularityTests` (every backend task) |
| Integration (Testcontainers PostgreSQL 18.6, real V1 to V3, `CentrifugoStub`, `AllocationStub`) | `ConversationSchemaIT`, `TenancyIT`, `MessageWriteIT`, `MessageConcurrencyIT`, `HistoryIT`, `ReadCursorIT`, `LifecycleIT`, `ConversationApiIT` (02); `OutboxPublisherIT`, `RealtimeTokenIT`, `TypingIT`, `OutboxAdminIT` (03); `AssignmentOrchestrationIT`, `AssignmentApiIT`, `PresenceIT` (04); `AllocationClientLiveIT` against the real engine, gated by `ALLOCATION_URL` (04); `RetentionIT`, `AdminApiIT`, `AdminTenantIT`, `ObservabilityIT` (05); `PlatformRaceIT` with `Invariants.assertAll` (07) |
| Client unit (Vitest) | `libs/chat-client`: `api.spec.ts`, `messages.spec.ts`, `sync.spec.ts`, `pending.spec.ts`, `typing.spec.ts`, `session.spec.ts`, `contracts.spec.ts`; widget `chat-platform-widget.spec.ts`; dashboard `inbox-list.spec.tsx`, `conversation-pane.spec.tsx` (06) |
| Contract (`tests/contract`) | `platform-api.spec.ts`: `statusConformsToStatusResponse`, `widgetSessionAndConversationConformToSchemas`, `messagePageConforms`, `agentInboxConforms`, `problemsCarryCodeAndNoSecrets`, `liveMessageCreatedEventConformsToSchema`, `liveConversationUpdatedEventConforms`, `liveTypingEventConforms`, `invalidConnectionTokenIsRejected`, `subscriptionTokenForAnotherChannelIsRejected` (06); `pnpm contracts:validate` |
| E2E (Playwright, two browser contexts, real stack) | `tests/e2e/conversation-platform.spec.ts` (`visitorAndAgentFullLifecycle`, master spec 12.3); `tests/e2e/resilience.spec.ts` (`refreshDuringActiveConversation`, `messageDuringBootstrapIsNotLost`, `centrifugoRestartConverges`, `duplicateSubmissionAfterTimeoutCreatesOneRow`, `typingIndicatorTimesOutWhenStopIsLost`, `agentGoesOfflineAndStalePresenceExpires`, `archivedConversationIsAbsentFromActiveListButRetrievable`, `reopenedConversationReturnsToInbox`, `nonParticipantCannotLoadHistoryOrSubscribe`); updated `widget.spec.ts`, `dashboard.spec.ts` (06) |
| Load (`tests/load`, official `centrifuge` SDK) | profiles `smoke`, `baseline`, `burst`, `reconnect-storm`, `soak`, `tenancy-skew`, `slow-consumer`, `target-10k` (stepped); `tests/load/invariants.mjs`; `EXPLAIN (ANALYZE, BUFFERS)` of the six master spec 8.3 queries in `docs/testing/query-plans.md` (07) |
| Manual drills | PostgreSQL, Centrifugo and allocation stopped and started with the observed responses recorded (05 T3); live allocation smoke (04 T4) |

Telemetry that proves it in Grafana/Prometheus: `message.send{outcome}`, `message.send.duration`,
`conversation.commands{command,outcome}`, `history.query`, `outbox.pending.count`, `outbox.pending.oldest_age_seconds`,
`outbox.parked.count`, `outbox.publish.attempts{outcome}`, `allocation.requests{operation,outcome}`,
`realtime.tokens{type,outcome}`, `rate_limit.rejections{scope}`, `retention.purge.conversations{result}`,
`api.problems{code}`; every response carries `X-Trace-Id`, every outbox row its `trace_id`.

Tests the Plan 01 rulings add to the plans (amendments recorded in the Plan 01 ledger):
`LifecycleIT.unarchivePurgedConversationIs409` and `versionIncrementsOnStateWritesOnly` (Plan 02, R-P8, R-P9); archive
releasing and unarchive re-requesting the assignment (Plan 02 `LifecycleIT`, Plan 04 claim SQL, R-P13); the reduced
contact view and event payloads (Plan 02, Plan 04, R-P3); `assignment_setup_failures` counting and reset (Plan 04, R-P2);
presence without `heartbeatAt` (Plan 04, R-P4); `sync.ts` 5-page cap and version-rule refetches (Plan 06 T1, R-P5);
`FAILED` rows released on resolve and archive and re-read every 60 s, a missing tenant on release, and `/assign`
reading the engine state after `CONVERSATION_NOT_ALLOCATABLE` (Plan 02 `LifecycleIT`, Plan 04
`failedRowProjectsLaterSweepAssignment`, `resolveOfFailedReleasesThroughEngine`, `releasePendingTenantMissingBecomesNone`,
`assignNotAllocatableProjectsEngineState`, R-P17); `RealtimeTokenIT.unknownConversationChannelIs404` (Plan 03 T3, R-P6).

Design rules still without a named test (to add in the plan that owns the code):
- `sync.ts`: a failed apply keeps the cursor and does a full reload (SI-6); an API head below `lastContiguous` does a
  full reload (SI-9 client side); the snapshot-plus-events property test over the three interleavings (SI-12)
  (Plan 06 T1).
- `ChannelAuthorizer`: a subscription-token request for an unknown **inbox** id answers 404
  ([tenant-security.md](../../../docs/architecture/tenant-security.md) section 5); Plan 03 T3 implements and tests only
  the conversation case (`RealtimeTokenIT.unknownConversationChannelIs404`), because telling an unknown inbox from
  another tenant's needs a second unfiltered statement that R-P6 does not allow. Open question for the plan owner.

## 9. Rollout, compatibility and rollback

- **Flags:** no feature flag for the API; `app.outbox.enabled` and `app.assignment.enabled` gate the worker ticks;
  `app.local-dev.enabled` gates local login, the admin API and the purge cutoff override (false by default, guarded in
  production).
- **Migration order:** the Flyway runner applies V3 before the backend starts (ADR-0003). V3 is additive, so rolling the
  application back to the previous image is safe (it ignores the new tables). Never edit V3 after it is applied; fix
  forward with a new `V<n>`. The V2 tables stay until a migration after Plan 06 drops them.
- **Demo removal:** Plan 06 T4 deletes the `chat` module, `/api/v1/demo/*`, the demo JSON Schemas and
  `tests/e2e/chat-mvp.spec.ts`, and marks `docs/features/local-chat-mvp.md` as superseded.
- **Widget embed compatibility:** `api-base-url` and `realtime-url` keep their meaning; new attributes `widget-key`
  (default `VITE_PUBLIC_WIDGET_KEY` or `wk_local_demo`), `external-id`, `display-name`; no attribute is renamed.
- **New runtime dependency:** the allocation service and its database in Compose (Plan 04); the CI E2E job is skipped
  with a logged reason where the engine checkout is absent.

## 10. ADRs and handover checklist

ADRs: [ADR-0005](../../decisions/ADR-0005-conversation-platform-modules.md) modules,
[ADR-0006](../../decisions/ADR-0006-message-write-and-ordering.md) write path and ordering,
[ADR-0007](../../decisions/ADR-0007-transactional-outbox-and-centrifugo.md) outbox and Centrifugo,
[ADR-0008](../../decisions/ADR-0008-tenant-isolation-and-local-identity.md) tenant isolation and local identity,
[ADR-0009](../../decisions/ADR-0009-allocation-service-integration.md) allocation integration. Each is "Accepted
(design)" until the plan named in its status has validated it.

- [ ] Contract updated and `pnpm contracts:validate` passes (every API-changing task of Plans 02 to 06)
- [ ] Migration V3 added; `pnpm db:migrate` then `pnpm db:validate` pass (Plan 02 T1)
- [ ] Module boundaries respected (`ModularityTests`, `pnpm lint`) after every task
- [ ] Tests added and run; `pnpm verify` passes; skipped tests carry their reason
- [ ] Architecture documents updated from "planned" to implemented names by the plan that implements them
- [ ] "LOCAL POC — NOT REAL AUTHENTICATION" kept on local login, widget demo identity and admin token flows
- [ ] Load reports state measured results only; the 10,000-client target is reported as validated only if a run at that
      size met its thresholds (Plan 07 T4)
- [ ] Reference checkouts unchanged (`docs/research/reference-snapshot.txt` re-check, Plan 07 T5); licence sign-off
      filled (`docs/research/license-review.md` section 8)
- [ ] Final report, test results and `HANDOVER.md` written (Plan 07 T5)
