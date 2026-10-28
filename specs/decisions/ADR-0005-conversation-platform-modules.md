# ADR-0005: Conversation platform modules and dependency directions

- **Status:** Accepted (design); validation pending in Plans 02 to 06 (`ModularityTests` after every backend task)
- **Date:** 2026-10-27
- **Master spec name:** ADR-001 "modular monolith and module boundaries" (mapping in
  [system-overview.md](../../docs/architecture/system-overview.md) section 6)
- **Builds on:** [ADR-0002](ADR-0002-modular-monolith.md) (one Spring Boot deployable with Spring Modulith modules)

## Context

The conversation platform adds tenancy, conversations, messages, lifecycle, read state, an outbox, allocation
integration, presence, typing and retention to `services/modulithplatformservice`. **Existing:** the service has the
modules `bootstrap`, `chat` (demo), `health`, `realtime`, `shared`, and `ModularityTests` verifies them with
`ApplicationModules.verify()`; the only cross-module edge is `chat → realtime`. Master spec 4.2 suggests a finer map
(`tenant`, `identity`, `inbox`, `contact`, `conversation`, `message`, `assignment`, `presence`, `attachment`,
`realtime`, `outbox`, `retention`, `audit`, `observability`) and asks to adapt it to the existing code.

The deciding constraint is the message write: one transaction locks the conversation row, applies the lifecycle
policy, consumes the conversation's next `sequence`, inserts the message, updates the conversation summary and appends
the outbox row ([ADR-0006](ADR-0006-message-write-and-ordering.md)). Whatever owns messages must own that lock.

## Options

1. **The spec's fine-grained map** (about 14 modules). Clear names, but `message` would need to lock and mutate
   conversation internals through a public API, or `conversation` and `message` would depend on each other (a cycle
   Spring Modulith rejects). `presence`, `attachment`, `audit` and `identity` would hold almost no code in the MVP.
2. **Six product modules by consistency boundary** (`tenancy`, `conversation`, `assignment`, `outbox`, `realtime`,
   `retention`), plus the existing `health`, `bootstrap`, `shared`. Fewer, larger modules; every edge points one way.
3. **Separate services.** Rejected by ADR-0002 and by overview §4 ("no extra microservice").
4. **One product module.** No enforced boundaries; the allocation gateway, the outbox and tenancy would blur.

## Decision

Option 2, as overview D2:

| Module | Responsibility | May depend on |
|---|---|---|
| `tenancy` | tenants, inboxes, contacts, agent roster, session tokens, caller resolution, local seed, rate limiter | `shared` |
| `conversation` | conversations, participants, messages, history, read cursors, lifecycle, state events, assignment orchestration (worker + projection), presence and typing endpoints, channel authorization | `tenancy`, `assignment`, `outbox`, `realtime`, `shared` |
| `assignment` | typed HTTP gateway to the allocation service, seeder | `tenancy` (admin tenants endpoint and seeding order only), `shared` |
| `outbox` | outbox table, appender, publisher worker, admin | `realtime`, `tenancy`, `shared` |
| `realtime` (existing) | Centrifugo publish/broadcast and token signing | `shared` |
| `retention` | retention policies, purge runs, purge, admin | `tenancy`, `shared` |
| `health` (existing) | status endpoint | `assignment`, `shared` |
| `chat` (existing demo) | demo API | `realtime`; removed in Plan 06 T4 |

- **No separate `message` module:** messages, the conversation row, participants, read cursors and state events form
  one consistency boundary under one row lock. Read cursors are clamped to `last_sequence`, state events are written in
  the command's transaction, typing and presence endpoints authorize against participation, and the assignment worker
  projects onto the conversation row; all of them live in `conversation`.
- Public types live in each module's root package (overview §5); everything else is under `internal/`. Classes under
  `internal/` that root-package tests use are `public`.
- Every module may use `shared`; a `package-info.java` with `@ApplicationModule(allowedDependencies = …)` lists
  `shared` explicitly. `shared` holds cross-cutting configuration only; no `utils` package.
- Verification: `ModularityTests` (Spring Modulith 2.1.2 `ApplicationModules.verify()`) runs after every backend task
  of Plans 02 to 06; a failing verification is fixed in the code, not by widening the table.

## Consequences

- `conversation` is the largest module (write path, lifecycle, history, worker, typing, tokens). Its `internal/`
  package is split by concern (`MessageService`, `ConversationService`, `HistoryService`, `AssignmentWorker`, …).
- `retention` deletes rows of conversation-owned tables with its own SQL and no Java dependency on `conversation`.
  Spring Modulith cannot see this coupling; the table ownership is documented in
  [archive-retention-purge.md](../../docs/architecture/archive-retention-purge.md) and the purge is covered by
  `RetentionIT`. Any schema change to those tables must update the purge.
- `assignment → tenancy` exists only for the admin tenants endpoint and seeding order; the allocation gateway itself
  needs no tenancy types.
- The demo `chat` module coexists until Plan 06 T4, so bean names must not clash (Plan 03 names its token controller
  `ConversationRealtimeTokenController`).

## Operational impact

- One deployable and one connection pool. The scheduled workers (`OutboxPublisher`, `AssignmentWorker`) run in every
  backend instance; they are safe to run concurrently because they claim rows with `FOR UPDATE SKIP LOCKED` and
  leases, and because of the per-row lease rule: before each Centrifugo or engine call a worker continues only if
  `now + call timeout` ≤ the row's lease end, otherwise it stops the rest of its batch, which another instance claims
  after the lease (R-P28; assignment batch size 20). The rule narrows but does not remove duplicates: the outbox may
  still publish an event twice (a crash between publish and mark, a late mark), which at-least-once delivery and the
  clients' deduplication by `eventId` absorb (ADR-0007).
- `app.outbox.enabled` and `app.assignment.enabled` gate only the scheduled ticks, so an operator can stop a worker in
  one instance without a redeploy of the code.

## Evidence that would trigger a change

- `ModularityTests` can only pass with an exception, an allowed cycle or a growing `allowedDependencies` list.
- Plan 07 load runs show the publisher or the assignment worker competing with request handling for CPU or connections
  (for example `hikaricp_connections_pending` > 0 during `baseline` caused by worker claims): extract that worker into
  its own process from the same codebase.
- A second team owns a slice of `conversation` (for example history and search), or attachments (NEXT) bring their own
  storage lifecycle large enough to justify an `attachment` module with a narrow API.
- A Spring Boot upgrade without a compatible Spring Modulith release (ADR-0002 consequence).
