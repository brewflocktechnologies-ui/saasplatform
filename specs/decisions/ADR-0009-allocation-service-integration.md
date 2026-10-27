# ADR-0009: Allocation service as a separate process behind an HTTP gateway

- **Status:** Accepted (design); validation pending in Plan 04 (`AllocationClientLiveIT` against the real engine,
  `AssignmentOrchestrationIT`, `AssignmentApiIT`, `PresenceIT`) and Plan 07 (load profiles, assignment counts)
- **Date:** 2026-10-27
- **Master spec name:** none (an addition to ADR-001 to ADR-004)
- **Details:** [allocation-integration.md](../../docs/architecture/allocation-integration.md); evidence:
  [allocation-service-integration.md](../../docs/research/allocation-service-integration.md)

## Context

**Existing:** the allocation engine (`D:\BYTEFLOCK\RESEARCH\Allocationservice-research\allocation-engine`) is a separate
Maven project: `backend/core` (pure Java), `backend/service` (Spring Boot 4.1.2, its own PostgreSQL schema and Flyway
V1 to V5), REST under `/api/v1`, local header auth (`X-Tenant-Id`, `X-Role`, `Idempotency-Key`), a fixed-delay dispatch
sweep, feature levels from `basic` to `workbench`, no Dockerfile, no outbound calls or webhooks
(allocation-service-integration.md sections 2 to 7). It owns assignment records, agent availability and capacity.
Master spec 11.4 requires reusing it, forbids a fictitious transaction across two services and forbids duplicating the
allocation algorithm. Overview §4 allows exactly one change to the engine: an added Dockerfile.

## Options

1. **Separate process over HTTP**: the engine in its own container with its own database; a typed client in the
   platform; the platform's conversation row is the durable request; a worker drives the engine and stores a projection;
   polling reconciles assignments the engine makes on its own.
2. **Embed the engine** (`allocation-core` as a library, its tables in the platform database). One transaction would be
   possible, but the dispatcher, persistence and policies live in the engine's Spring Boot service, so embedding means
   forking or changing the engine.
3. **Shared database** between the two services. No real shared transaction (each service owns its schema and
   migrations) and a hidden coupling of schemas.
4. **Push notifications from the engine** (webhook). Not available: the engine makes no outbound call; adding one is an
   engine change.
5. **A broker between the two.** New infrastructure.

## Decision

Option 1 (overview D8, D9):

- Compose runs `allocation-service` (host 8081, container 8080) and `allocation-postgres` (host 5433); feature level
  `operations` (claim and reassignment); engine demo seeding off; the engine's sweep stays on (2 s in Compose).
- `assignment.AllocationClient` (JDK `HttpClient`, 3 s timeout) sends `X-Tenant-Id` = our tenant id, `X-Role` =
  `operator` (`admin` for seeding), `Idempotency-Key` on dispatch, claim and reassign, and `X-Correlation-Id`. Ids are
  shared: our tenant id, our conversation UUID, `inboxes.allocation_queue_id`, our agent id.
- The conversation row stores the projection (`assignment_state`, `assigned_agent_id`, `assignment_decision_id`,
  `assignment_reason`, `assignment_explanation`) and the request bookkeeping. `AssignmentWorker` claims due rows with
  `FOR UPDATE SKIP LOCKED` and a lease, calls the engine outside any transaction, and projects the result in a new
  transaction guarded by an `assignment_state` precondition: `REQUESTED → dispatch`, `QUEUED → poll`,
  `RELEASE_PENDING → resolve`, `REOPEN_PENDING → requeue REOPEN`. Worker keys are `assign:<conversationId>:<attempt>`.
- Agents set availability with `PUT /api/v1/agent/presence`, forwarded to the engine (60 s heartbeat TTL, 30 s
  dashboard heartbeat); the platform keeps no second availability store.
- Projection changes emit `conversation.updated` through the outbox (ADR-0007).

## Consequences

- Assignment is eventually consistent: an assignment the engine makes in its sweep reaches the platform within about
  one sweep plus one poll (2 s + 5 s with the Compose values). The UI shows `REQUESTED`/`QUEUED` meanwhile.
- An engine outage never blocks chat: agents can send to any conversation of their tenant; only assignment, `/assign`
  and presence wait.
- Polling costs one `GET /api/v1/conversations/{id}` per waiting conversation every 5 s, and each dispatch attempt leaves
  an idempotency record and a decision row in the engine.
- The platform must branch on every engine outcome (HTTP 200 does not mean assigned) and reconcile non-idempotent calls
  (resolve, requeue) by reading the record.
- Two databases, one more container and image, and a CI E2E job that runs only where the engine checkout exists.

## Operational impact

- Ports 8081 and 5433; `ALLOCATION_DB_PASSWORD`, `ALLOCATION_URL`, `ALLOCATION_ENGINE_DIR` in `.env`.
- Seeding: `AllocationLocalSeeder` (local profile) and `POST /api/v1/admin/tenants`; a reset engine database is repaired
  by re-seeding (the worker backs off on `TENANT_NOT_FOUND` for up to 30 attempts).
- `/api/v1/status` reports the engine (`allocation`); `allocation.requests{operation, outcome}`; conversations in
  `FAILED` are visible in the dashboard queue and need an agent's `/assign`.

## Evidence that would trigger a change

- Plan 07 measures polling load that matters: with many waiting conversations the 5 s poll grows linearly (for example
  10,000 waiting conversations would mean about 2,000 requests per second); or the projection lag is a product complaint.
  Then add the **webhook upgrade path**: an additive, configurable `afterCommit` notification in the engine (one
  registration in the dispatcher's persist step plus the conversation transitions, allocation-service-integration.md
  section 7.1) that calls a platform endpoint which only marks the conversation due; the worker still reads the record,
  so polling remains the reconciliation.
- The engine gains outbound events or a client library: replace hand-written calls where it reduces risk.
- Capacity or availability must change synchronously with a platform transaction (for example strict "agent may not
  exceed N even for a few seconds" across both systems): revisit option 2.
- The engine's API changes (new required headers, renamed outcomes): update the client and `AllocationClientLiveIT`
  first.
