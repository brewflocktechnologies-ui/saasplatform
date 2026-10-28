# Conversation platform: system overview

Design document of Plan 01 Task 7 (master spec section 4). Plans 02 to 07 implement it and cite it. Status: **design,
accepted for implementation; nothing here is validated by a test yet**. Each plan replaces "planned" wording with the
implemented names when it lands.

## 0. How to read these documents

- **Existing** marks a fact about the code at Plan 01's base commit, with the path that proves it. Everything else is a
  design decision and cites its source: `D<n>` is a decision of the plan overview
  ([00-overview.md](../superpowers/plans/2026-10-27-conversation-platform-00-overview.md) section 2), "§5" is that
  file's interface glossary, `R<n>` and `R-P<n>` are Plan 01 controller rulings restated in section 7 below (`R-P<n>`
  were made after the first review of these documents and supersede anything that contradicts them).
- Precedence, one order for every document (R-P31 M13): the rulings register (section 7) > these design documents
  (this set, the ADRs and the feature spec) > the plan overview (`00-overview.md`) > Plans 02 to 07. A difference is
  reported in the ledger, never resolved silently.
- Delivery of realtime events is at least once. No document in this set promises a stronger guarantee.
- 10,000 concurrent clients is a design target that stays unvalidated until measured. Plan 07 T4 measures it in tiers
  (master spec 1) and reports the largest passing step.

| Document | Answers |
|---|---|
| This file | components, ports, modules, failure modes, decision sources, deliverable map |
| [domain-model.md](domain-model.md) | entities, V3 tables, state separation, conversation and assignment state machines, identifiers |
| [message-lifecycle.md](message-lifecycle.md) | send transaction, idempotency, ordering, history, read cursors, limits, delete vs purge |
| [realtime-and-recovery.md](realtime-and-recovery.md) | channels, envelope, events, outbox publisher, client bootstrap and reconnect, Zulip invariants |
| [tenant-security.md](tenant-security.md) | tokens, tenant source per endpoint, participant and channel rules, admin, rate limits, local-only shortcuts |
| [archive-retention-purge.md](archive-retention-purge.md) | resolve / archive / reopen / delete / purge, eligibility, batches, run record, operator procedure |
| [allocation-integration.md](allocation-integration.md) | allocation topology, worker and projection, keys, poll/backoff, `/assign`, presence, failure matrix |
| [ADR-0005](../../specs/decisions/ADR-0005-conversation-platform-modules.md) … [ADR-0009](../../specs/decisions/ADR-0009-allocation-service-integration.md) | the five decisions with options, consequences and change triggers |
| [feature.spec.md](../../specs/features/conversation-platform/feature.spec.md) | user stories, acceptance criteria, API list, test plan, rollout |

## 1. Components

Interactive diagram: [diagrams/conversation-platform.html](diagrams/conversation-platform.html) (source
[conversation-platform.architecture.json](diagrams/conversation-platform.architecture.json), rendered with the archify
skill; re-render with `archify finalize architecture <json> <html> --quality showcase`). Text version:

```text
 Browser (localhost)                                   WS events (at least once)
   Visitor widget  :5173  ◄──────────────────────────── Centrifugo v6.10.2 :8000 ◄── POST /api/broadcast ── OutboxPublisher
   Agent dashboard :3000  ◄─────────────────────────────┘        ▲ typing (direct, no outbox)            (claim lease, mark)
        │ REST + bearer session JWT                              │                                               │
        └──────────────────────────────────────────► Platform API :8080 ── 1 tx: message + outbox row ──► PostgreSQL 18 :5432
                                                       │ /assign, presence (HTTP)                               ▲ claim, project
                                                       ▼                                                        │
                                   Allocation service :8081 ◄── dispatch · poll · resolve (HTTP) ── AssignmentWorker
                                   (own container) ── allocation-postgres :5433
```

OutboxPublisher and AssignmentWorker are scheduled components inside the same Spring Boot process as the Platform API
(`modulithplatformservice`); they are drawn apart because they run outside request transactions.

| Component | Runtime | Host port | Role | Status |
|---|---|---|---|---|
| Visitor widget | Lit 3 custom element `<chat-platform-widget>`, Vite 8 (`apps/chatwidget`) | 5173 | visitor UI; session, send with `clientMessageId`, typing, presence badge, lifecycle states | Existing app, extended in Plan 06 |
| Agent dashboard | Next.js 16 App Router, `/agent` (`apps/chatdashboardapp`) | 3000 | agent inbox (queue / mine / all, archived), thread, actions, presence | Existing app, extended in Plan 06 |
| `libs/chat-client` | TypeScript, `centrifuge` 5.8.0 | none | typed APIs, Centrifuge wiring, conversation sync, pending queue, typing, sessions | Existing lib, rewritten in Plan 06 T1 |
| Platform API | Spring Boot 4.1.2, Java 25, Spring Modulith 2.1.2 (`services/modulithplatformservice`) | 8080 | REST, auth, write path, workers, token signing | Existing service, new modules in Plans 02 to 05 |
| PostgreSQL | `postgres:18.6-alpine3.24`, schema `platform` | 5432 | source of truth for conversations, messages, outbox, cursors, audit | Existing; V3 added in Plan 02 |
| Flyway runner | `flyway/flyway:13.11.1-alpine` one-shot job (ADR-0003) | none | applies `V1…V3` as `platform_migrator` | Existing |
| Centrifugo | `centrifugo/centrifugo:v6.10.2` (`infra/centrifugo/config.json`) | 8000 | WebSocket fan-out; namespace `chat`; client publish denied | Existing; history/recovery keys added in Plan 03 |
| Allocation service | the existing engine (`allocation-engine/`), own container | 8081 (container 8080) | assignment records, agent availability, capacity | New container in Plan 04 (D8) |
| allocation-postgres | `postgres:18.6-alpine3.24`, database `allocation` | 5433 | the engine's own schema (its Flyway V1 to V5) | New in Plan 04 |
| Observability | OTel Collector, Prometheus 9090, Tempo, Loki, Grafana 3001 | see inventory | traces, logs, metrics | Existing, unchanged |

Ports, variables and run modes: [workspace-inventory.md](../research/workspace-inventory.md) sections 3.3 and 3.4;
allocation container variables: [allocation-integration.md](allocation-integration.md) section 1.

## 2. Modules and allowed dependencies (D2, ADR-0005)

One Spring Modulith module per top-level package under `com.byteflock.chatplatform`. **Existing:** `bootstrap`,
`chat`, `health`, `realtime`, `shared` (`services/modulithplatformservice/src/main/java/com/byteflock/chatplatform/`);
`ModularityTests` runs `ApplicationModules.verify()`; the only cross-module imports today are `chat → realtime`
(`chat/ChatService.java`, `chat/RealtimeTokenController.java`).

| Module | Owns (tables, behaviour) | Public API (overview §5) | May depend on |
|---|---|---|---|
| `tenancy` (Plan 02) | `tenants`, `inboxes`, `contacts`, `agents`; session tokens, caller resolution (`AuthInterceptor`), `LocalDevGuard`, `LocalSeeder`, widget session and local-login endpoints, `RateLimiter` | `PrincipalType`, `Caller`, `@RequiresPrincipal`, `Inbox`, `Contact`, `Agent`, `TenantDirectory`, `SessionTokens`, `IssuedSession`, `LocalDevProperties`, `RateLimiter` | `shared` |
| `conversation` (Plans 02 to 04) | `conversations`, `conversation_participants`, `messages`, `message_attachments`, `conversation_read_cursors`, `conversation_state_events`; write path, lifecycle, history, read cursors, `AssignmentWorker` and projection, presence and typing endpoints, `ChannelAuthorizer` and realtime token endpoints | `ConversationStatus`, `AssignmentState`, `AssignmentInfo`, `AssignmentView`, `ContactAssignmentView` (R-P29), `ConversationView`, `MessageView`, `MessagePage`, `SendMessageRequest`, `CommandRequest`, `AssignMode`, `AssignRequest`, `Channels`, `InboxRef` | `tenancy`, `assignment`, `outbox`, `realtime`, `shared` |
| `assignment` (Plan 04) | typed HTTP gateway to the allocation service, `AllocationSeeder`, `AllocationLocalSeeder`, admin tenants endpoint (Plan 05) | `Availability`, `AllocationResult`, `AllocationConversationState`, `AgentPresenceView`, `AllocationClient`, `AllocationUnavailableException`, `AllocationRejectedException`, `SeedAgent`, `AllocationSeeder` | `tenancy` (admin tenants endpoint and seeding order only), `shared` |
| `outbox` (Plans 02, 03) | `outbox_events`; `OutboxAppender`, `OutboxPublisher`, admin list/replay | `OutboxAppender`, `EventEnvelope`, `OutboxStatus`, `OutboxEventView`, `Backoff` | `realtime`, `tenancy`, `shared` |
| `realtime` (existing) | Centrifugo server API client and token signing (`realtime/RealtimePublisher.java`, `realtime/RealtimeTokenIssuer.java`); gains `broadcast` in Plan 03 | `RealtimePublisher`, `BroadcastResult` (R-P30), `RealtimeTokenIssuer`, `IssuedToken` | `shared` |
| `retention` (Plan 05) | `retention_policies`, `purge_runs`; purge over conversation-owned tables by SQL, no Java dependency on `conversation` | `PurgeRequest`, `PurgeRunView`, `RetentionService` | `tenancy`, `shared` |
| `health` (existing) | `/api/v1/status` (`health/StatusController.java`); gains the `allocation` dependency in Plan 04 | none | `assignment`, `shared` |
| `bootstrap` (existing) | `/api/v1/bootstrap` | none | `shared` |
| `shared` (existing) | cross-cutting web config, problem details, trace id, `Clock` (`shared/ClockConfig.java`); gains `Problems` in Plan 02 | `Problems`, `Clock` bean | none |
| `chat` (existing demo) | `/api/v1/demo/*`, V2 tables | none | `realtime`; **removed in Plan 06 T4** |

Rules: no other edge is allowed; every module may use `shared`, and a `package-info.java` that declares
`@ApplicationModule(allowedDependencies = …)` must list `shared` too (D2). Classes under `internal/` that a test in the
module's root test package uses are `public` (Plan 02 global constraints). No `utils` package (overview §4).

## 3. Main flows

| Flow | Path | Detail |
|---|---|---|
| Visitor or agent sends a message | API → one PostgreSQL transaction (lock, participant check, idempotency, lifecycle policy, sequence, message, conversation, outbox row; R-P24) → 201 → OutboxPublisher → Centrifugo `broadcast` → both channels → clients | [message-lifecycle.md](message-lifecycle.md) section 1, [realtime-and-recovery.md](realtime-and-recovery.md) section 4 |
| Client loads or reconnects | subscribe first, then `GET …/messages` (`after=` or newest page), merge by `sequence` | [realtime-and-recovery.md](realtime-and-recovery.md) sections 5 and 6 |
| New conversation is assigned | `POST /widget/conversations` writes `assignment_state = REQUESTED` → AssignmentWorker → allocation create + dispatch → projection → `conversation.updated` | [allocation-integration.md](allocation-integration.md) section 2 |
| Agent changes lifecycle | `POST …/resolve`, `/reopen`, `/pending`, `/archive`, `/unarchive` under row lock and version check | [domain-model.md](domain-model.md) section 4 |
| Operator purges | admin dry run, then bounded batches | [archive-retention-purge.md](archive-retention-purge.md) |

Invariant for every flow (overview §4): PostgreSQL commits before any acknowledgement, and Centrifugo, the allocation
service and the browser are never called inside a transaction or while a row lock is held.

## 4. Failure modes (master spec 15)

| Unavailable | Platform API | OutboxPublisher | AssignmentWorker | Widget / dashboard | Operator signal |
|---|---|---|---|---|---|
| **PostgreSQL** | Every database-backed request fails with 503 `DATABASE_UNAVAILABLE` (problem+json, no SQL text); no write is acknowledged, so no 201 exists for an uncommitted message. Widget sessions and local login also need the database and fail the same way. The process stays up (**existing:** Hikari `initialization-fail-timeout: -1`, workspace-inventory.md 5.2). | Claim statement fails; logged; next tick (500 ms) retries. Nothing is lost: rows stay `PENDING`. | Same; next tick (2 s). | Send shows the bubble as `failed` with Retry (same `clientMessageId`); history loads fail with an error banner. The realtime connection may stay up but carries no new events. | `GET /api/v1/status` `database` DOWN, `status` DEGRADED; `api.problems{code=DATABASE_UNAVAILABLE}` (Plan 05 T3). |
| **Centrifugo** | Message writes still answer 201 after commit; the outbox row waits as `PENDING`. Typing answers 202 and the signal is dropped. Token endpoints still sign tokens (no network call). | `broadcast` fails → `attempts` counted at claim, backoff `min(1 s × 2^(attempts−1) ± 20 %, 60 s)` (0-based `Backoff.next` index `attempts − 1`, R-P15, clamped to [0, 30], R-P27). An outage is a transport failure (connection refused, timeout, 5xx, `temporary` error): rows keep retrying every 60 s at most and never park, and drain when Centrifugo returns (R-P30). Only non-transport failures park: a row that Centrifugo keeps rejecting (an error object that is not `temporary`, or HTTP 4xx) parks after about 4 minutes (attempts 1 to 9 wait 1+2+4+8+16+32+60+60+60 = 243 s, then attempt 10 parks) and waits for admin replay. | Unaffected. | Badge `Reconnecting` / `Offline`; after Centrifugo returns, every `subscribed` reconciles from the API (R5), so clients converge even for events that were delayed or parked. | `outbox.pending.count`, `outbox.pending.oldest_age_seconds`, `outbox.parked.count`; status `centrifugo` DOWN. |
| **Allocation service** | Chat keeps working: agents read and send to any non-archived conversation of their tenant (D14). `POST …/assign` → 503 `ALLOCATION_UNAVAILABLE`; `PUT /agent/presence` → 503; `agentOnline` is `null` for widgets. | Unaffected. | Calls raise `AllocationUnavailableException`; the row keeps its `assignment_state` (`REQUESTED`, `QUEUED`, `RELEASE_PENDING`, `REOPEN_PENDING`) and retries with backoff up to 60 s. | Dashboard shows the assignment as waiting (queue view includes `REQUESTED`); widget presence shows Unknown. | Status `allocation` DOWN (Plan 04 T4); `allocation.requests{outcome}`. |
| **Object storage** | N/A in the MVP: no upload endpoint and no object storage (D15). The NEXT design keeps an attachment `PENDING` until the object is confirmed, so it is never falsely `READY`. | n/a | n/a | n/a | n/a |
| **Client offline** | Nothing to do: the server holds no per-client state. | Events keep flowing to Centrifugo; the client misses them. | Presence heartbeats stop; the agent becomes stale for new allocations after 60 s; nothing is released automatically (allocation-integration.md F15). | Unsent messages stay `pending`/`failed` locally and are never shown as accepted; retry reuses `clientMessageId`; on reconnect the client reconciles from the API. | none |
| **Backend process crash** | In-flight requests fail; committed data is safe. | Leased rows are reclaimed after the 30 s lease; a crash after publish and before mark republishes the same `eventId` (a duplicate clients drop). Workers check the lease before every call and stop a batch that would outlive it (R-P28). | Leased rows are reclaimed after the 30 s lease and processed with the next key. | Clients reconnect and reconcile. | Restart only. |

Plan 05 T3 verifies the first three rows with manual drills and records the observed responses.

## 5. Scale and capacity

The design target is 10,000 concurrent chat clients. It is unvalidated until measured. The design choices that bound
it (per-conversation row lock, outbox poll delay and batch size, single-node in-memory Centrifugo engine, in-memory
rate limits, polling assignment) are named in the ADRs with the measurement that would change them. Plan 07 T3/T4 run
the profiles `smoke`, `baseline`, `burst`, `reconnect-storm`, `soak`, `tenancy-skew`, `slow-consumer` and, where the
host allows, `target-10k` in steps, and report only completed measurements.

## 6. Master spec 17 deliverables and naming

**ADRs live in `specs/decisions/`** (the repository's existing convention, ADR-0001 to ADR-0004), not in the `docs/adr/`
directory the master spec suggests. The spec's ADR names map as follows:

| Master spec name (section 4) | This repository |
|---|---|
| `docs/adr/ADR-001-modular-monolith-and-module-boundaries.md` | [specs/decisions/ADR-0005-conversation-platform-modules.md](../../specs/decisions/ADR-0005-conversation-platform-modules.md) |
| `docs/adr/ADR-002-message-write-and-ordering.md` | [specs/decisions/ADR-0006-message-write-and-ordering.md](../../specs/decisions/ADR-0006-message-write-and-ordering.md) |
| `docs/adr/ADR-003-transactional-outbox-and-Centrifugo.md` | [specs/decisions/ADR-0007-transactional-outbox-and-centrifugo.md](../../specs/decisions/ADR-0007-transactional-outbox-and-centrifugo.md) |
| `docs/adr/ADR-004-tenant-isolation.md` | [specs/decisions/ADR-0008-tenant-isolation-and-local-identity.md](../../specs/decisions/ADR-0008-tenant-isolation-and-local-identity.md) |
| (no spec counterpart) | [specs/decisions/ADR-0009-allocation-service-integration.md](../../specs/decisions/ADR-0009-allocation-service-integration.md) |

Master spec 17 "Research and design" deliverables:

| Deliverable | File |
|---|---|
| `docs/research/workspace-inventory.md` | [workspace-inventory.md](../research/workspace-inventory.md) |
| `docs/research/reference-comparison.md` | [reference-comparison.md](../research/reference-comparison.md) (evidence notes: [chatwoot.md](../research/evidence/chatwoot.md), [rocketchat.md](../research/evidence/rocketchat.md), [zulip.md](../research/evidence/zulip.md)) |
| `docs/research/allocation-service-integration.md` | [allocation-service-integration.md](../research/allocation-service-integration.md) |
| `docs/research/license-review.md` | [license-review.md](../research/license-review.md) |
| `docs/architecture/system-overview.md` | this file |
| `docs/architecture/domain-model.md` | [domain-model.md](domain-model.md) |
| `docs/architecture/message-lifecycle.md` | [message-lifecycle.md](message-lifecycle.md) |
| `docs/architecture/realtime-and-recovery.md` | [realtime-and-recovery.md](realtime-and-recovery.md) |
| `docs/architecture/tenant-security.md` | [tenant-security.md](tenant-security.md) |
| `docs/architecture/archive-retention-purge.md` | [archive-retention-purge.md](archive-retention-purge.md) |
| `docs/adr/*` | `specs/decisions/ADR-0005` to `ADR-0009` (table above) |
| (master spec 4, not in 17) `docs/architecture/allocation-integration.md` | [allocation-integration.md](allocation-integration.md) |

## 7. Decision sources: Plan 01 rulings

The controller's rulings are recorded in the Plan 01 ledger,
`saasplatform/.superpowers/sdd/2026-10-27-conversation-platform-01-research-and-architecture/progress.md`, a local file
that git ignores (R1). They are restated here so the documents stand alone; other documents cite them as `R<n>`.

| Id | Ruling |
|---|---|
| R1 | Execution ledgers and task briefs live in `saasplatform/.superpowers/sdd/<plan>/` (git-ignored), not in `D:\BYTEFLOCK\.superpowers\sdd\` as the plans say. Plan 07 documents name this path. |
| R2 | `sequence` is gap-free: assigned as `last_sequence + 1` under the conversation row lock (D4). It numbers messages only. Tombstones keep their sequence; purge removes a conversation's messages wholesale and archives it. |
| R3 | `conversation.created` and `conversation.updated` carry the conversation `version` (monotonic per conversation); `typing` carries neither counter. A conversation event whose `version` exceeds the known one by more than 1, or any conversation event while the client is not caught up, triggers a refetch of the conversation view. |
| R4 | Centrifugo recovery has failed only when the subscribed context has `wasRecovering = true && recovered = false` (centrifugal.dev/docs/server/history_and_recovery, read 27 Oct 2026). A bare `recovered = false` on a first subscribe is not a failure. |
| R5 | The client reconciles with `?after=<lastContiguous>` on every `subscribed` (D12), also after a successful recovery. The delta fetch pages at most 5 times with limit 200; if `hasMore` is still true, the client takes the full-reload path (newest page, older pages on demand). |
| R6 | The conversation channel stays `chat:conversation:<uuid>` without a tenant segment (D12). Isolation comes from D14: subscription tokens are issued only after the tenant and participant check and are bound to the exact channel. The inbox channel keeps the tenant: `chat:inbox:<tenantId>:<inboxUuid>`. |
| R7 | No per-tenant display number in the MVP (DEFER); the conversation UUID is the only identifier. |
| R8 | `PENDING` means "waiting for the contact" and is set only by the agent `/pending` command. A contact message into `PENDING` moves it to `OPEN` (state event `REOPENED_BY_CONTACT`, `conversation.updated` with `change = REOPENED`, assignment unchanged); an agent message into `PENDING` keeps `PENDING`. |
| R9 | Sender and participant types are the V3 check set `CONTACT`, `AGENT`, `BOT`, `SYSTEM`; the MVP writes only `CONTACT` and `AGENT`. |
| R10 | Tenant isolation is enforced in application SQL (every statement filters `tenant_id`) plus tests (D3, D14). No PostgreSQL row-level security and no composite `(tenant_id, id)` foreign keys in the MVP; RLS is the ADR-0008 upgrade trigger (a second service or ad-hoc SQL access to the database). Cross-table tenant consistency is asserted by Plan 07 `Invariants.assertAll`. |
| R11 | A history page is one `SELECT`, a consistent snapshot under READ COMMITTED; no REPEATABLE READ is needed. |
| R12 | The read cursor is monotonic (`GREATEST`) and clamped to the conversation's `last_sequence`. The sender's own cursor advances to the new message's sequence inside the send transaction, so own messages never count as unread. |
| R13 | Every conversation transition runs under `SELECT … FOR UPDATE` on the conversation row **and** writes with `UPDATE … WHERE id = :id AND version = :expected` (0 rows → `STALE_VERSION`). Assignment projections use an `assignment_state` precondition. |
| R14 | Event `data` shapes: `message.created` `{message: MessageView}`; `conversation.created` / `conversation.updated` `{conversation: ConversationView without the per-caller fields unreadCount and agentOnline, change}` (`change` only on `updated`); `typing` `{participantType, participantId, typing}`. |
| R15 | Event consumers (browser clients, contract tests that read live events) ignore unknown fields; producers and the JSON Schemas used by producer-side contract tests stay strict (`additionalProperties: false`). |
| R16 | MVP audit trail = `conversation_state_events` (resolve, reopen including by contact, pending, archive/unarchive, assignment outcomes, release) + `purge_runs` for administrative purge + outbox admin replay logged at INFO with `eventId`. Authentication and session audit is NEXT (local identity only). |
| R-P1 | `QUEUED` rows only poll (`findConversation`); dispatch happens from `REQUESTED` (and after a reopen requeue); `CONFLICT_RETRYABLE` keeps the row `REQUESTED` with next = now + poll-delay. |
| R-P2 | New column `conversations.assignment_setup_failures int NOT NULL DEFAULT 0`: incremented only on setup-code rejections (`TENANT_NOT_FOUND`, `QUEUE_NOT_FOUND`, `NO_ACTIVE_POLICY`), reset to 0 on any successful allocation response, `FAILED` at `app.assignment.max-setup-attempts`; `assignment_attempts` stays monotonic because it feeds the idempotency key. |
| R-P3 | Contact-facing conversation data hides assignment internals: for contact callers and in every `conversation.created`/`updated` payload, `assignment` carries only `state` and `agentId` (`decisionId`, `reason`, `explanation` absent; the shape is `ContactAssignmentView`, R-P29); agents read them through `GET /api/v1/agent/conversations/{id}`, which the dashboard calls when an event changes the open conversation. |
| R-P4 | Presence forwarding omits `heartbeatAt` (the allocation service stamps its own clock): `AllocationClient.presence(tenantId, agentId, status)`. |
| R-P5 | `sync.ts` caps delta catch-up at 5 pages (limit 200) then takes the full-reload path, and refetches the conversation view on a version jump > 1, on any conversation event while not caught up, and on every `subscribed`. |
| R-P6 | The only statement on a tenant-owned table without a tenant filter is the authorization probe `ConversationRepository.tenantAndInboxOf(UUID id)`, returning only `(tenantId, inboxId, contactId)` to answer 403 versus 404. Amended: statements keyed only by primary key are allowed inside a transaction after a tenant-filtered lock or lookup of that row (for example `bumpSequence`, the guarded `UPDATE`, outbox marks by `event_id` + worker). |
| R-P7 | Nullable columns are exactly those listed in [domain-model.md](domain-model.md) section 3 plus the later-set timestamps. |
| R-P8 | A satisfied command is a 200 no-op even on an archived conversation; the archived rule precedes `INVALID_TRANSITION`; `resolved_at` is kept on reopen; `UNARCHIVE` of a purged conversation is 409 `INVALID_TRANSITION`. Amended by R-P31 (M14): on an archived conversation `REOPEN` of `OPEN` and `MARK_PENDING` of `PENDING` are 409 `CONVERSATION_ARCHIVED`; only `RESOLVE` and `ARCHIVE` stay satisfied no-ops. |
| R-P9 | `version` increments on status, archive, purge and assignment-projection writes only (not message activity or worker bookkeeping); the purge `UPDATE` adds `version = version + 1`; purge emits no realtime event. Sharpened by R-P23. |
| R-P10 | Worker: `REQUESTED`/`QUEUED` with the allocation record `RESOLVED` → `requeueReopen`; `CANCELLED` → `FAILED`; any other 4xx → `FAILED` with the code (as amended by R-P14). `/assign`: `CONFLICT_RETRYABLE` → 409 `ALLOCATION_REJECTED`; `expectedVersion` checked (409 `STALE_VERSION`). |
| R-P11 | History `before` and `after` together → 400 `VALIDATION_FAILED`; no cursor → newest page; admin token compared in constant time (`MessageDigest.isEqual`). |
| R-P12 | Ledgers live in `saasplatform/.superpowers/sdd/<plan>/` (restates R1); research-versus-overview differences follow the overview; one inbox (queue) per tenant in MVP seeding and in the load harness (documented limitation). |
| R-P13 | Archived conversations hold no allocation capacity: `ARCHIVE` of a conversation whose `assignment_state` is `REQUESTED`, `QUEUED`, `ASSIGNED` or `REOPEN_PENDING` sets `RELEASE_PENDING`; the worker never dispatches archived rows; `UNARCHIVE` of an `OPEN`/`PENDING` conversation sets `REOPEN_PENDING`; status changes on archived conversations stay 409 `CONVERSATION_ARCHIVED`. |
| R-P14 | Engine errors: 409 `CONCURRENT_MODIFICATION` and `IDEMPOTENCY_RACE` are transient; `RELEASE_PENDING` never goes `FAILED` (unknown 4xx → back off, WARN); `/assign` leaves the projection unchanged on claim-specific rejections and answers 409 `ALLOCATION_REJECTED`; `NOT_ASSIGNED` on `REASSIGN` → `findConversation`, project, then 409; `ASSIGNED` rows are not reconciled in the background in the MVP (documented limitation). |
| R-P15 | `outbox.Backoff.next(now, n, base, max, rng) = now + min(base × 2^n ± 20 %, max)` with a 0-based `n`; callers pass `attempts − 1`. `n` is clamped to [0, 30] (R-P27). |
| R-P16 | Unavailability backoff uses the monotonic claimed attempt count (`assignment_attempts`), so a long-queued conversation waits the 60 s cap after its first engine outage. Accepted: the engine's own sweep keeps assigning queued conversations while it is up, and during an outage everything waits anyway. |
| R-P17 | `FAILED` rows must not hold engine capacity silently: resolve (T1) and archive (T5) move `FAILED` to `RELEASE_PENDING` like the other non-`NONE` states; in `RELEASE_PENDING` an engine 404 of any code (`CONVERSATION_NOT_FOUND`, `TENANT_NOT_FOUND`) is confirmed with `findConversation` and, with the record absent, becomes `NONE`; `FAILED` rows are claimed every `app.assignment.failed-poll` (60 s) for a `findConversation`-only check (engine `ASSIGNED`/`ACCEPTED`/`ACTIVE` → `projectAssigned` with reason `ALLOCATION_SWEEP`, otherwise they stay `FAILED`, no dispatch); `/assign` answered `REJECTED_INVALID_INPUT` with `CONVERSATION_NOT_ALLOCATABLE` runs `findConversation` and projects the engine state before answering (200 when it is now assigned to the caller, else 409 `ALLOCATION_REJECTED`). A `FAILED` projection can coexist with an engine `QUEUED` record that the sweep later assigns. |
| R-P18 | Inbox channel authorization needs no probe, because `chat:inbox:<tenantId>:<inboxUuid>` carries the tenant: a channel tenant different from the caller's tenant → 403; the same tenant but no such inbox (tenant-filtered lookup) → 404; `CONTACT` callers → 403 for any inbox channel. |
| R-P19 | A repeated "Assign to me" (`CLAIM`) by the agent who already holds the conversation answers 200 with the unchanged view (idempotent); a different agent gets 409 `ALLOCATION_REJECTED`. It follows from R-P17's `findConversation`-then-project. |
| R-P20 | Moves out of `FAILED` (resolve/archive → `RELEASE_PENDING`, unarchive → `REOPEN_PENDING`, `/assign` → `REQUESTED`) set `assignment_next_attempt_at = now`, not `GREATEST(current, now)`: the `FAILED` re-read time is a schedule, not an in-flight lease, and a concurrent re-read cannot project over the new state because projections carry an `assignment_state` precondition. Every other move keeps `GREATEST(current, now)`. An engine error while re-reading a `FAILED` row reschedules to `failed-poll`, not to backoff. |
| R-P21 | The hidden assignment fields (`decisionId`, `reason`, `explanation`) are absent, not null, in every event payload and in contact-facing API responses; agent API responses keep them (null when unknown). This matches the strict event schemas. Its serialisation detail (an `AssignmentView` written without null fields) is superseded by R-P29. |
| R-P22 | An agent of tenant A requesting `chat:inbox:<A>:<id of an inbox of tenant B>` gets 404 (the tenant-filtered lookup in A finds nothing), not 403: it reveals less than 403 and follows R-P18 literally. |
| R-P23 | `version` increments if and only if a `conversation.updated` (or `conversation.created`) outbox row is appended in the same transaction. Every projection write that changes `(assignment_state, assigned_agent_id)` appends exactly one event, with `change` `ASSIGNED`, `ASSIGNMENT_QUEUED` or `ASSIGNMENT_FAILED` where they fit and the new value `ASSIGNMENT_UPDATED` otherwise (release to `NONE`, moves into `REQUESTED`, `RELEASE_PENDING`, `REOPEN_PENDING`); a lifecycle transition that also moves the assignment is covered by its one lifecycle event. A `QUEUED` poll that finds the conversation still `QUEUED` is bookkeeping (it may update the stored reason): no `version` bump, no event. The purge stays exempt (R-P9). |
| R-P24 | The idempotency lookup runs after the participant check and before the lifecycle policy: a retry with the same `clientMessageId` and fingerprint returns the original message (200) even if the conversation has since been resolved or archived; a purged conversation has no messages, so a retry there falls through to the lifecycle policy (409). |
| R-P25 | Purge eligibility = not purged AND ((`status = 'RESOLVED'` AND `resolved_at < cutoff`) OR (`archived_at IS NOT NULL` AND `archived_at < cutoff`)); for rows eligible only by the second branch the purge also sets `status = 'RESOLVED'` and `resolved_at = COALESCE(resolved_at, now)`, so "purged ⇒ `RESOLVED` ∧ archived" holds; the purge writes no state event (`purge_runs` is the audit) and no realtime event; `ix_conversations_purge_due` covers both branches. |
| R-P26 | `ReadCursorService` takes a tenant-filtered `SELECT … FOR KEY SHARE` on the conversation row before the cursor upsert, so cursor writes and sends lock in the same order (conversation, then cursor) and a first read cannot deadlock (`40P01`) with a first send. |
| R-P27 | `Backoff.next` clamps the retry index to [0, 30] before exponentiating: the monotonic attempt counter reaches 62 and more within minutes of `QUEUED` polling. |
| R-P28 | Workers check their lease per row: before each engine (or Centrifugo) call a worker continues only if `now + call timeout` ≤ the row's claimed lease end, otherwise it stops processing the rest of the batch (those rows are reclaimed after the lease). The assignment worker also re-reads `(archived_at, assignment_state)` right before a create, dispatch or claim call and skips the row if either changed since the claim (the worker itself makes create and dispatch calls only). New `app.assignment.batch-size` = 20. Concurrent instances are safe under this rule; the outbox may still publish an event twice (at least once). |
| R-P29 | Contact-facing assignment data is the dedicated two-field record `ContactAssignmentView(state, agentId)` with both keys always present (`agentId` null when none); event payloads use the same shape; agent responses use the full `AssignmentView`. Supersedes R-P21's serialisation detail; `decisionId`, `reason`, `explanation` stay absent for contacts and in events. |
| R-P30 | Outbox parking counts only non-transport failures (Centrifugo error objects that are not `temporary`, and HTTP 4xx); transport failures (connection refused, timeout, 5xx, `temporary` errors) keep retrying at the max backoff and never park, so a Centrifugo outage does not park a window of events. Plan 03's poison test uses a non-temporary error object. |
| R-P31 | Final-review minors: M1 `/assign` on an archived conversation → 409 `CONVERSATION_ARCHIVED`; M2 `view=queue` includes `REOPEN_PENDING`; M3 message rate-limit key `msg:<tenant>:<type>:<principal>`; M4 contact session refresh limited to 30 days after the first issue (`app.sessions.contact-refresh-max-age=30d`); M5 `unreadCount = 0` when `purged_at` is set, and `ConversationView` gains a nullable `purgedAt`; M6 the archive row of [archive-retention-purge.md](archive-retention-purge.md) includes `FAILED`; M7 race tests compare by `sequence` (a `RESOLVED` state event's `details` stores `lastSequence`); M9 legal hold re-checked before every purge batch; M10 purge nulls `archive_reason` and removes the free-text `reason` from that conversation's state-event `details`; M11 the engine's `REASSIGN` exclusion is permanent (documented); M12 a persistence-choice paragraph in ADR-0006; M13 one precedence order: this register > design documents > overview > Plans 02 to 07; M14 `REOPEN` on an archived `OPEN` and `MARK_PENDING` on an archived `PENDING` conversation → 409 `CONVERSATION_ARCHIVED` (amends R-P8); M15 `recordSetupFailure`/`resetSetupFailures` carry the `expectedFrom` precondition; M16 claim queries use `WITH … AS MATERIALIZED`; M17 Plan 07 invariant: after the workers drain, `RESOLVED` ⇒ `assignment_state = NONE`; M18 the backup/restore runbook restores the platform and allocation databases together; and engine 409 `AGENT_VANISHED` is transient. |

Two procedural rulings are not design input and are not restated: work happens on branch `feat/conversation-platform`,
and the ten-line copy limit applies to the reference repositories, not to the allocation engine.
