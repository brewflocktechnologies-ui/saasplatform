# Allocation service integration study

Phase 1 artifact of the conversation-platform plan set (master spec section 2.2, "Discover the existing allocation service", plus the integration decision of overview D8/D9). Plan 04 implements `AllocationClient` and `AllocationSeeder` from the call table in section 4; Plans 02, 05 and 07 cite the rest.

- **Date:** 2026-10-27 (machine local time +0530).
- **Method:** read-only study of the engine's documentation and code, plus one build check of the `core` module (section 9). The service was not started, no service test was run, nothing under `allocation-engine/` was edited. The build check wrote only inside `backend/core/target/`, which already existed.
- **Convention:** a plain statement is *observed* in the file named next to it (`path:line`). **Documented, not re-run** marks a claim taken from a project document that this task did not re-verify. **Inference** marks my reading of observed facts. **Recommendation** marks a design choice offered to Plan 04 that the code does not force. **Not found** is a finding, not a gap.
- **External documentation consulted:** none. Every source is a repository file. Base-image names and Docker behaviour in section 8 are not checked against any registry.
- **Related:** runtime facts (ports, env vars, database image, JDK pin, the missing Dockerfile, port collisions) are in [workspace-inventory.md](workspace-inventory.md) section 6 and are not repeated here. Section 6 below adds only what changes for the platform.
- **Paths.** `allocation-engine/` = `D:\BYTEFLOCK\RESEARCH\Allocationservice-research\allocation-engine`. Abbreviations: `svc/` = `allocation-engine/backend/service/src/main/java/com/byteflock/allocation/service/`; `core/` = `allocation-engine/backend/core/src/main/java/com/byteflock/allocation/core/`; `res/` = `allocation-engine/backend/service/src/main/resources/`; `IT/` = `allocation-engine/backend/service/src/test/java/com/byteflock/allocation/service/`; `docs/` = `allocation-engine/docs/`. Controllers live in `svc/api/`, so `CatalogController:56` means `svc/api/CatalogController.java:56`. Likewise `Dispatcher`, `ConversationService`, `Limits`, `ApiException`, `DispatchScheduler`, `DemoSeeder`, `FeatureGuard` are in `svc/application/`; `CatalogRepository`, `ConversationRepository`, `PolicyRepository`, `SnapshotBuilder`, `AssignmentStatus` in `svc/persistence/`; `LocalDevAuthInterceptor`, `WebConfig`, `ApiExceptionHandler`, `OverviewController` in `svc/api/`.

## 1. Read this first: what bites

Details and sources are in the sections named. Each item changes what Plan 04 writes.

1. **HTTP 200 does not mean "assigned".** `dispatch`, `claim` and `reassign` answer 200 with an `AllocationDecision` for every outcome, including `REJECTED_INVALID_INPUT`, `CLAIM_REJECTED`, all `QUEUED_*` and `CONFLICT_RETRYABLE`. Branch on `outcome` (sections 2.4, 5.4).
2. **An idempotency key replays its stored decision for ever**, even when the answer was "queued" or "rejected" and the conversation has changed since. After a reopen or a reassign the key must be new. The same key for a different operation or body is 409 `IDEMPOTENCY_KEY_REUSED` (section 5.2).
3. **No published policy covering the queue means no allocation.** `dispatch` answers 409 `NO_ACTIVE_POLICY` and the scheduler silently skips the queue. `AllocationSeeder` must create and publish a policy, and the policy JSON must carry every field (sections 4.1, 7.3).
4. **`POST /agents` is a full replace.** An omitted `status` resets the agent to `OFFLINE`, an omitted `enabled` to `true`, and the membership list is replaced. Re-seeding must not clobber live presence (section 4.1).
5. **`requeue` defaults are hostile:** `excludePreviousAgent` defaults to `true` and `reason` to `TRANSFER`. The excluded-agents list can only grow; no endpoint clears it. `reassign` always excludes the previous agent, so a single-agent tenant cannot reassign (sections 4.3, 7.2).
6. **`resolve` and `requeue` REOPEN are not idempotent.** A repeat answers 409 (`CONVERSATION_CLOSED`, `INVALID_REQUEUE`). Treat that as success only after a `GET` confirms the state (section 7.2).
7. **The sweep assigns behind the platform's back**, and nothing is pushed. The platform polls `GET /conversations/{id}`. That response has no `decisionId`; for sweep assignments it comes only from `GET /conversations/{id}/assignments` (sections 6.1, 7.1).
8. **Level `operations` is required.** At the default `basic`, claim, reassign and requeue answer 403 `FEATURE_DISABLED` (section 6.2).
9. **Demo seeding must be off, and `X-Tenant-Id` must always be sent.** A missing header falls back to `demo-tenant` (section 6.3).
10. **Presence is a push, a 60 s default TTL and no automatic release.** The engine returns no "effective availability" flag, and nothing unassigns an agent's conversations when it goes offline (sections 2.3, 4.2).
11. **Do not generate a client from `docs/api/openapi.yaml`.** It lists a bogus required `caller` query parameter on 58 operations and documents no error responses (section 10).
12. **Ids must match `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`.** A UUID string passes (section 2.5).

## 2. Domain model (Step 1)

### 2.1 Conversation status and transitions

`ConversationStatus` (`core/domain/ConversationStatus.java:10-15`): `QUEUED, ASSIGNED, ACCEPTED, ACTIVE, RESOLVED, CANCELLED`. Only `QUEUED` is allocatable (`:21-23`); `ASSIGNED`, `ACCEPTED` and `ACTIVE` consume capacity (`:17-19`). The database enforces the same six values (`res/db/migration/V1__schema.sql:54`).

| From | To | Caused by | Source |
|---|---|---|---|
| (none) | `QUEUED` | `POST /conversations` | `ConversationService:50-74` |
| `QUEUED` | `ASSIGNED` | dispatch, claim, reassign, scheduler sweep (writes an `assignment` row and sets `assignedAgentId`) | `Dispatcher:381-392` |
| `ASSIGNED` | `ACCEPTED` | `POST /conversations/{id}/accept` (else 409 `INVALID_TRANSITION`) | `ConversationService:88-90, 159-169` |
| `ACCEPTED` | `ACTIVE` | `POST /conversations/{id}/activate` | `ConversationService:92-94` |
| `QUEUED`, `ASSIGNED`, `ACCEPTED`, `ACTIVE` | `RESOLVED` | `POST /conversations/{id}/resolve`; closes the active assignment as `COMPLETED`; `assignedAgentId` stays on the row | `ConversationService:96-107` |
| any state except `RESOLVED`, `CANCELLED` | `CANCELLED` | `DELETE /conversations/{id}`; closes the assignment as `CANCELLED` | `ConversationService:109-120` |
| `ASSIGNED`, `ACCEPTED`, `ACTIVE` | `QUEUED` | `requeue` with reason `TRANSFER`, `REJECT`, `TIMEOUT` or `REASSIGN`, and `reassign`; previous agent added to `excludedAgentIds` when `excludePreviousAgent`; `enqueuedAt` reset to now; `assignedAgentId` cleared | `ConversationService:131-157` |
| `QUEUED` | `QUEUED` in another queue | `requeue` `TRANSFER` with a different `targetQueueId`; or the policy fallback `move-to-queue` | `ConversationService:137-140`; `Dispatcher:393-402` |
| `RESOLVED` | `QUEUED` | `requeue` with reason `REOPEN` (only from `RESOLVED`); `createdAt` kept, `enqueuedAt` = now | `ConversationService:139, 153-155` |

The platform needs only create, dispatch/claim/reassign, resolve and reopen. `accept` and `activate` exist, but capacity counts `ASSIGNED` like the other two, so the platform never has to call them (inference from `AssignmentStatus:7-9`).

### 2.2 Assignments, the one-active index and capacity

- Assignment statuses (`AssignmentStatus:5`): `ASSIGNED, ACCEPTED, ACTIVE, COMPLETED, REJECTED, TIMED_OUT, TRANSFERRED, REASSIGNED, CANCELLED`. Only the first three consume capacity (`:7-9`). Requeue reasons map to the final status: `TRANSFER` to `TRANSFERRED`, `REJECT` to `REJECTED`, `TIMEOUT` to `TIMED_OUT`, `REASSIGN` to `REASSIGNED`, `REOPEN` to `COMPLETED` (`ConversationService:26-28`).
- **One active assignment per conversation:** partial unique index `assignment_one_active_per_conversation` on `(tenant_id, conversation_id)` where status is `ASSIGNED`, `ACCEPTED` or `ACTIVE` (`res/db/migration/V1__schema.sql:79-80`). It is the last of three guards against double assignment, after the `FOR UPDATE SKIP LOCKED` row lock and the `status = 'QUEUED'` precondition on the update (`ConversationRepository:56-58, 67-76`; `docs/architecture/concurrency-and-consistency.md`, "Why it is correct").
- **Capacity is never stored.** The load of an agent is counted from `assignment` rows in the policy's counted statuses (default `ASSIGNED, ACCEPTED, ACTIVE`; the validator requires `ASSIGNED` in the set) (`SnapshotBuilder:34-39`; `ConversationRepository:107-125`; `core/policy/CapacityConfig.java:17-18`; `core/policy/PolicyValidator.java:133`). The winner is recounted under its row lock (`Dispatcher:341-371`). The limit is `agent.maxActiveAssignments` when set, else the policy's `capacity.defaultMaxActive`; `null` means unlimited and `0` means "accepts nothing" (documented in `docs/architecture/domain-model.md`, "Capacity semantics"; the database check `max_active_assignments >= 0` is at `V1__schema.sql:25`). Resolving a conversation frees its slot at once (`ConversationService:102`).
- The queue snapshot reads at most 500 waiting conversations, ordered by `enqueued_at, id` (`SnapshotBuilder:22, 42`; `ConversationRepository:46`).

### 2.3 Agent availability and presence

- Columns on `agent`: `status` (`ONLINE`, `AWAY`, `OFFLINE`; `core/domain/AvailabilityStatus.java:5-7`), `last_heartbeat_at`, `enabled` (`V1__schema.sql:22-24`). The engine never observes presence; callers push it (`CatalogController:129-138`). The other writers are `POST /agents`, which sets `status` and, for a new agent, a heartbeat of now (`CatalogController:118-125`), and `DemoSeeder:119-125`, which touches the demo tenant only.
- The `availability` filter, mandatory in every policy (`core/strategy/eligibility/AvailabilityFilter.java:44-58`): `OFFLINE` fails with `AGENT_OFFLINE`; `AWAY` fails with `AGENT_AWAY` unless the policy param `allowAway` is true; no heartbeat fails with `PRESENCE_UNKNOWN`; heartbeat age in whole seconds greater than `heartbeatTtlSeconds` fails with `PRESENCE_STALE`. **The TTL is a per-policy param with default 60 s (range 1 to 86400)** (`:32`). An age of exactly 60 s is still fresh, 61 s is stale, and a heartbeat in the future counts as fresh (`:54-55` and the class comment).
- `POST /agents/{id}/presence` writes `status` and `last_heartbeat_at` together (`CatalogRepository:118-121`). `heartbeatAt` omitted means server now. A value more than 60 s in the future is 400 `TIMESTAMP_IN_FUTURE`. A value in the past has no lower bound, so a stale `heartbeatAt` is accepted and makes the agent stale at once (`CatalogController:135`; `Limits:105-111`).
- An omitted `status` keeps the current one, but the read and the write are separate statements (`CatalogController:133-136`). **Recommendation:** always send `status` with every heartbeat so a concurrent status change cannot be overwritten, and omit `heartbeatAt` so the engine stamps its own clock.
- **Not found:** any code that releases or reassigns an agent's conversations when the agent goes `OFFLINE` or stale. The filter only affects *new* allocations. If the platform wants that behaviour it must call `reassign` or `requeue` itself.
- **Not found:** an "effective availability" field. `GET /agents` returns raw `status` and `lastHeartbeatAt`; the "stale after 60 s" decision (overview D9) is computed by the platform. Keep the policy's `heartbeatTtlSeconds` and the platform's stale-after value equal.

### 2.4 Outcomes

`Outcome` (`core/domain/Outcome.java:5-21`). Every outcome is returned in the body of a 200 response. "Stored" means the decision row **and** the idempotency record are written, so the same key replays it (`Dispatcher:425-432`). Only `CONFLICT_RETRYABLE` is built outside that path (`Dispatcher:485-495`) and is never stored.

| Outcome | Meaning and code path | Stored | Retryable? | Reachable from the platform's targeted dispatch, claim or reassign |
|---|---|---|---|---|
| `ASSIGNED` | one agent chosen; conversation `ASSIGNED`; `agentId` set | yes | n/a | yes |
| `QUEUED_NO_ELIGIBLE_AGENT` | some candidate failed a filter that is neither availability nor capacity (membership, disabled, excluded, skill, language), or there are no candidates (`DefaultAllocationEvaluator:196-208`) | yes | the **sweep** retries every 5 s; a re-dispatch with the same key only replays | yes |
| `QUEUED_CAPACITY_EXHAUSTED` | every candidate failed only on capacity (`:199-201`) | yes | as above | yes |
| `QUEUED_NO_AVAILABLE_AGENT` | every candidate failed on availability or capacity (`:202-204`) | yes | as above | yes |
| `QUEUED_MANUAL_SELECTION` | policy `mode` is `MANUAL`; nobody auto-selected (`:210-218`) | yes | claims only | no (needs `allocation.mode.manual` and a `MANUAL` policy) |
| `QUEUED_FEATURE_DISABLED` | the policy needs an eligibility feature that is off (`:140-143`) | yes | no; configuration | only if flags change after publication |
| `CLAIM_REJECTED` | the claiming agent failed a hard filter; the second reason code is the failing filter (`:189-195`) | yes | only after the condition changes, with a new key | claim only |
| `NO_WAITING_CONVERSATION` | nothing allocatable (`:136-138`) | yes | n/a | no: a targeted request always has its conversation as the selected one (`:109-139`, inference) |
| `SKIPPED_POLICY_DISABLED` | `enabled` is false inside the published definition (`:96-98`) | yes | no; fix the policy | yes if the seeded JSON omits `enabled` (section 7.3) |
| `REJECTED_INVALID_INPUT` | reason `CONVERSATION_NOT_ALLOCATABLE` (not `QUEUED`), `AGENT_NOT_FOUND` (claimer not in the queue's agent set), `CONVERSATION_NOT_FOUND`, `QUEUE_NOT_IN_POLICY_SCOPE`, `TENANT_MISMATCH`, `DUPLICATE_ID`, `NEGATIVE_LOAD`, `INVALID_INPUT` (`:83-86, 112-127, 150-153`) | yes | **no** | yes: `CONVERSATION_NOT_ALLOCATABLE`, and `AGENT_NOT_FOUND` on a claim; the other codes cannot occur on this path (inference) |
| `CONFLICT_RETRYABLE` | three attempts lost races; the explanation says "safe to retry with the same Idempotency-Key" (`Dispatcher:493`) | **no** | **yes, same key** | yes |
| `FAILED_INTERNAL` | evaluator exception or an unknown strategy name (`:68-74`) | yes | no; a replay returns the same failure, so use a new key after the cause is fixed | only on a corrupt policy |

Decision fields the platform maps: `decisionId` (UUID text), `outcome`, `agentId`, `conversationId`, `reasonCodes[]` (enum names), `explanation` (text), plus `policyId`, `policyVersion`, `evaluatedAt` and the full candidate trace (`core/domain/AllocationDecision.java:19-41`). The reason-code catalogue is `core/domain/ReasonCode.java:4-44`.

### 2.5 Identifier rule (`Limits.id`)

`Limits.id` (`Limits:32, 37-46`): the value must be non-blank, at most 128 characters, and fully match `[A-Za-z0-9][A-Za-z0-9._:-]*`. Otherwise 400 `FIELD_REQUIRED` (null or blank) or `INVALID_ID`. It is applied to: tenant `id` (`CatalogController:60`), queue `id` (`:76`), agent `id` (`:100`), membership `queueId` (`Limits:123`), conversation `conversationId`, `queueId` and `language` (`ConversationService:51-57`) and `policyId` (`PolicyLifecycleService:38`). It is **not** applied to the `X-Tenant-Id` header, to `claim.agentId`, to `targetQueueId` or to path variables; those are looked up as given.

**A UUID string passes.** Checked by running the same pattern through `java.util.regex.Pattern.matcher(...).matches()` in `jshell` on JDK 25: `550e8400-e29b-41d4-a716-446655440000` matches (36 characters, starts with a digit, `-` is allowed), `agent:1.x_y` matches, `a@b` and `-x` do not. Agent ids and tenant ids chosen by the platform must satisfy the same rule when posted; an id containing `@`, a space or `/` is rejected with `INVALID_ID`.

The `Idempotency-Key` is bounded differently: trimmed, at most 128 characters, any characters (`Limits:62-72`).

### 2.6 Policy building blocks and their flags

A policy is typed data: one conversation selection, an ordered list of eligibility filters, one agent ranking, a tie-break, a capacity rule and a fallback (`core/policy/PolicyDefinition.java:14-30`). Publication validates it and refuses on any issue (`PolicyLifecycleService:65-79`). Flags: `core/feature/Features.java:10-60`.

| Part | Built-in strategies | Needs a flag? |
|---|---|---|
| Conversation selection | `fifo` | no (basic) |
| | `oldest-created`, `priority-then-fifo`, `priority-aging` | `allocation.selection.oldest-created`, `.priority`, `.priority-aging` (level `routing`) |
| Eligibility (mandatory, all six in every policy: `PolicyValidator:114`) | `tenant`, `agent-enabled`, `queue-membership`, `availability`, `capacity`, `excluded-agents` | no |
| Eligibility (optional) | `required-skills`, `language` | `allocation.eligibility.skills`, `.language` (level `routing`) |
| Agent ranking | `round-robin` (per-queue cursor row locked during dispatch) | no (basic) |
| | `least-loaded`, `least-recently-assigned`, `fewest-total-assigned` | `allocation.ranking.*` (level `balanced`) |
| Capacity | `count-based` | no |
| Fallback | `keep-queued` | no |
| | `move-to-queue` | `allocation.fallback.overflow-queue` (level `operations`) |
| Tie-break | `AGENT_ID` | no; `SEEDED_RANDOM` needs `allocation.tie-break.seeded-random` (level `balanced`) |
| Mode | `AUTO` | no; `MANUAL` needs `allocation.mode.manual` (level `operations`) |

The full explanation of each strategy and the 18-flag catalogue are in `docs/architecture/feature-levels-and-packaging.md` and `docs/architecture/allocation-flow.md` (documented, not re-run). Preview, simulation, comparison and regression endpoints need `workbench`-level flags and are not used by the platform.

## 3. Conventions that apply to every call

**Headers** (the interceptor covers `/api/**` only, `WebConfig:28`; `/actuator/**` and the OpenAPI/Swagger paths need none):

| Header | Rule | Source |
|---|---|---|
| `X-Tenant-Id` | The tenant. Trimmed. Missing or blank means `demo-tenant` (`allocation.default-tenant`, which has no environment variable). Not validated against the id rule; the tenant must exist (404 `TENANT_NOT_FOUND`), except on the catalogue endpoints and `/api/v1/health`. Ignored by `POST /tenants`, which creates the tenant named in the body. | `LocalDevAuthInterceptor:40, 55-58`; `res/application.yaml:46`; `CatalogController:59-63` |
| `X-Role` | `viewer` (default), `operator` or `admin`; trimmed and lower-cased; anything else is 400 `UNKNOWN_ROLE`. `admin` satisfies `operator`, which satisfies `viewer`. | `LocalDevAuthInterceptor:41-44, 68-74`; `Caller:10-16` |
| `Idempotency-Key` | Required on dispatch, claim and reassign. Missing header is 400 `BAD_REQUEST` ("Missing request header Idempotency-Key."); blank is 400 `IDEMPOTENCY_KEY_REQUIRED`; over 128 characters after trimming is 400 `IDEMPOTENCY_KEY_TOO_LONG`. | `AllocationController:52, 58`; `ConversationController:105`; `Limits:62-72`; `ApiExceptionHandler:54-57` |
| `X-Correlation-Id` | Optional. Echoed in the response header and in the error body; a UUID is generated when absent. | `LocalDevAuthInterceptor:37-39`; `ApiExceptionHandler:34` |
| `Content-Type: application/json` | Needed whenever a body is sent; otherwise 415 `UNSUPPORTED_MEDIA_TYPE`. `resolve`, `reassign` and `publish` take no body. | `ApiExceptionHandler:97` |

Authentication is these headers only and is explicitly local-development (`LocalDevAuthInterceptor:15-20`, `res/application.yaml:49-51`). Anything that can reach the port can act as any tenant and role. Do not publish the allocation port beyond the platform's own network (inference; overview D8 already keeps it on a private network).

**Order of checks** for a request: correlation id; role validity (400); required role (403); tenant existence (404); then Spring's argument binding (missing header, malformed or mistyped body: 400 `BAD_REQUEST`); then the controller and services (field limits 400; feature guard 403; lookups 404; state conflicts 409). Consequence: a wrong role on an unknown tenant is 403, not 404 (`LocalDevAuthInterceptor:48-58`).

**Errors on every call.** Body shape `{status, code, message, issues[], correlationId}` (`ApiExceptionHandler:30`). `issues[]` is filled only for policy validation (422).

| Status | Code | When | Source |
|---|---|---|---|
| 400 | `UNKNOWN_ROLE` | `X-Role` not one of the three | `LocalDevAuthInterceptor:42-44` |
| 403 | `FORBIDDEN` | role below the endpoint's minimum | `LocalDevAuthInterceptor:52-54` |
| 404 | `TENANT_NOT_FOUND` | tenant does not exist. The interceptor skips the check for the catalogue endpoints (S1, S2, S3, S8, S9, P1, P2) and for `/api/v1/health`; S3 and S9 repeat it themselves, S2 and P2 return `[]`, S8 and P1 answer 404 `AGENT_NOT_FOUND` | `LocalDevAuthInterceptor:55-58` |
| 400 | `BAD_REQUEST` | malformed JSON, wrong field type, unknown enum value, missing required header or body | `ApiExceptionHandler:42-63` |
| 413 | `PAYLOAD_TOO_LARGE` | body over 5 MiB (`ALLOCATION_MAX_REQUEST_BYTES`) | `RequestSizeFilter:31-37, 46-49` |
| 415, 405, 404 | `UNSUPPORTED_MEDIA_TYPE`, `METHOD_NOT_ALLOWED`, `NOT_FOUND` | wrong media type, verb or path | `ApiExceptionHandler:86-102` |
| 409 | `CONFLICT` | a database constraint was violated after a lost check-then-insert race | `ApiExceptionHandler:80-84` |
| 500 | `INTERNAL_ERROR` | anything unhandled. A database failure is not special-cased, so it ends here (inference) | `ApiExceptionHandler:104-109` |

Time values are ISO-8601 UTC instants (for example `2026-10-27T03:00:00Z`). The service reads its own `Clock.systemUTC()` (`svc/config/CoreConfig.java:49-53`).

Unknown JSON properties in a request: **not verified** (no test or code path states it). Send only the fields listed in section 4. Read responses leniently: the decision carries many fields the platform ignores.

## 4. The call table (Step 2)

Every row needs `X-Tenant-Id` (the platform tenant id) and `X-Role` (the minimum shown; overview D8 uses `operator`, and `admin` for seeding). "Errors" lists the codes observed in code in addition to the common errors in section 3. Request and response field lists are in section 4.7.

### 4.1 Seeding calls (`AllocationSeeder`, role `admin`)

| ID | Call | Min role | Request body | Success | Errors (status `code`) | Source |
|---|---|---|---|---|---|---|
| S1 | `POST /api/v1/tenants` | `admin` | `{"id": "<tenant>", "name": "<name>"}` | 201, the request echoed (`name` echoed as sent, even if null) | 400 `FIELD_REQUIRED`, `INVALID_ID`, `TEXT_TOO_LONG` | `CatalogController:56-64`; `CatalogRepository:30-33` |
| S2 | `GET /api/v1/queues` | `viewer` | none | 200 `[QueueRecord]` ordered by id; an unknown tenant gives `[]` | none | `CatalogController:66-69` |
| S3 | `POST /api/v1/queues` | `admin` | `{"id": "<queueId>", "name": "<name>", "description": "<text>"}` | 201 `QueueRecord` | 404 `TENANT_NOT_FOUND`; 400 `FIELD_REQUIRED`, `INVALID_ID`, `TEXT_TOO_LONG` | `CatalogController:71-82`; `CatalogRepository:43-47` |
| S4 | `GET /api/v1/policies/{policyId}` | `viewer` | none | 200 `PolicyRecord` (`state`, `activeVersion`, `draft`) | 404 `POLICY_NOT_FOUND` | `PolicyController:59-62`; `PolicyLifecycleService:112-114` |
| S5 | `GET /api/v1/policies` | `viewer` | none | 200 `[PolicyRecord]` | none | `PolicyController:47-50` |
| S6 | `POST /api/v1/policies` | `admin` | a full `PolicyDefinition` (section 7.3) | 201 `PolicyRecord`, `state` `DRAFT`, `activeVersion` null | 409 `POLICY_EXISTS`; 400 `FIELD_REQUIRED` (`name`), `INVALID_ID`, `TEXT_TOO_LONG`, `TOO_MANY_ITEMS`; 404 `TENANT_NOT_FOUND` | `PolicyController:52-57`; `PolicyLifecycleService:37-46, 105-110` |
| S7 | `POST /api/v1/policies/{policyId}/publish` | `admin` | none | 200 `VersionRecord` | 404 `POLICY_NOT_FOUND`; 422 `POLICY_INVALID` with `issues[]` | `PolicyController:78-82`; `PolicyLifecycleService:65-79` |
| S8 | `GET /api/v1/agents/{agentId}` | `viewer` | none | 200 `AgentRecord` | 404 `AGENT_NOT_FOUND` | `CatalogController:89-92` |
| S9 | `POST /api/v1/agents` | `admin` | `AgentRequest` (section 4.7) | 201 `AgentRecord` (also when replacing an existing agent) | 404 `TENANT_NOT_FOUND`, `QUEUE_NOT_FOUND`; 400 `FIELD_REQUIRED`, `INVALID_ID`, `TEXT_TOO_LONG`, `TOO_MANY_ITEMS`, `INVALID_VALUE`, `INVALID_MEMBERSHIP`, `DUPLICATE_MEMBERSHIP`, `INVALID_CAPACITY`, `FEATURE_DISABLED` (non-null `maxActiveInQueue` below level `balanced`) | `CatalogController:94-127` |
| S10 | `GET /api/v1/features` (optional) | `viewer` | none | 200 `[FeatureView]` (`key, level, enabled, source, requires, description, usedBy`) | none | `FeatureController:29-32`; `FeatureService:25` |

Behaviour notes:

- **S1 is idempotent and never updates.** `insert … on conflict (id) do nothing`: a second call is a silent 201 and the stored `name` is not changed. There is no tenant update or delete endpoint (not found).
- **S3 is an upsert, not a conflict.** `on conflict (tenant_id, id) do update set name, description`. A repeat returns 201 and overwrites `name` and `description` (a null `name` becomes the id, `CatalogController:79`).
- **S6 is not an upsert:** an existing `policyId` is 409 `POLICY_EXISTS`. The server forces `tenantId` to the caller's and `version` to 1 (`PolicyLifecycleService:43`). Validation is deferred: an invalid body is stored as a draft (201) and fails at S7 with 422 (`IT/api/AllocationApiIT.java:115-124`).
- **S7 is not idempotent:** every call inserts the next version number and makes it active (`PolicyLifecycleService:72-77`; `PolicyRepository:78-88`). Publish only when `activeVersion` is null. A policy that was `DISABLED` stays disabled after publishing (`PolicyRepository:84-86`).
- **S9 is a full replace** (`CatalogRepository:58-78`; `CatalogController:118-125`): `status` omitted becomes `OFFLINE`, `enabled` omitted becomes `true`, `maxActiveAssignments` omitted becomes null (unlimited), `skills`/`languages` omitted become empty, and **all memberships are deleted and re-inserted from the body**. `last_heartbeat_at` and the assignment counters of an existing agent are kept; a new agent gets a heartbeat of "now". Each `memberships[].queueId` must already exist (404 `QUEUE_NOT_FOUND`). **Recommendation:** `GET` the agent first (S8). Create only when it is absent. When it exists and must change, resend its current `status`, `enabled` and the union of memberships.
- `maxActiveAssignments`: `null` = unlimited, `0` = accepts nothing, negative = 400 `INVALID_CAPACITY`. `SeedAgent.maxActiveAssignments` is an `int` in overview section 5, so 0 would drain an agent: validate it in Plan 04.
- A new agent needs a membership in the inbox's queue or the `queue-membership` filter rejects it (`QUEUED_NO_ELIGIBLE_AGENT`). Adding an inbox later means re-posting each agent with the extended membership list.

### 4.2 Presence and directory

| ID | Call | Min role | Request body | Success | Errors (status `code`) | Source |
|---|---|---|---|---|---|---|
| P1 | `POST /api/v1/agents/{agentId}/presence` | `operator` | `{"status": "ONLINE", "heartbeatAt": "2026-10-27T03:00:00Z"}`; both fields optional but the body itself is required (`{}` is valid) | 200 `AgentRecord` | 404 `AGENT_NOT_FOUND` (also for an unknown tenant); 400 `TIMESTAMP_IN_FUTURE`; 400 `BAD_REQUEST` (bad `status` value or no body) | `CatalogController:129-138`; `Limits:105-111`; `IT/api/HardeningIT.java:113-117` |
| P2 | `GET /api/v1/agents` | `viewer` | none | 200 `[AgentRecord]` for the whole tenant, ordered by id; no filter, no paging | none | `CatalogController:84-87`; `CatalogRepository:80-83` |

`AgentPresenceView(agentId, availability, lastHeartbeatAt, enabled)` maps to `AgentRecord.id`, `status`, `lastHeartbeatAt`, `enabled`.

### 4.3 Conversation lifecycle (role `operator`)

| ID | Call | Min role | Request body | Success | Errors (status `code`) | Source |
|---|---|---|---|---|---|---|
| V1 | `POST /api/v1/conversations` | `operator` | `{"conversationId": "<uuid>", "queueId": "<allocation_queue_id>", "createdAt": "<iso>", "enqueuedAt": "<iso>"}` (other fields optional, section 4.7) | 201 `ConversationSnapshot`, `status` `QUEUED`, `assignedAgentId` null | 404 `TENANT_NOT_FOUND`, `QUEUE_NOT_FOUND`; 409 `CONVERSATION_EXISTS` (or `CONFLICT` on a lost race); 400 `FIELD_REQUIRED`, `INVALID_ID`, `INVALID_PRIORITY`, `TIMESTAMP_IN_FUTURE`, `TOO_MANY_ITEMS`, `INVALID_VALUE` | `ConversationController:47-52`; `ConversationService:50-74` |
| V2 | `GET /api/v1/conversations/{id}` | `viewer` | none | 200 `ConversationSnapshot` | 404 `CONVERSATION_NOT_FOUND` (also when the platform never created it) | `ConversationController:54-57`; `ConversationService:76-78` |
| V3 | `POST /api/v1/conversations/{id}/resolve` | `operator` | none | 200 `ConversationSnapshot`, `status` `RESOLVED` | 404 `CONVERSATION_NOT_FOUND`; 409 `CONVERSATION_CLOSED` (already `RESOLVED` or `CANCELLED`); 409 `CONCURRENT_MODIFICATION` (retry) | `ConversationController:83-87`; `ConversationService:96-107, 180-184` |
| V4 | `POST /api/v1/conversations/{id}/requeue` | `operator` | `{"reason": "REOPEN", "excludePreviousAgent": false}` | 200 `ConversationSnapshot`, `status` `QUEUED`, `assignedAgentId` null | 403 `FEATURE_DISABLED` (`allocation.ops.reassignment`); 404 `QUEUE_NOT_FOUND` (target), `CONVERSATION_NOT_FOUND`; 409 `INVALID_REQUEUE`, `CONVERSATION_CLOSED` (cancelled), `CONCURRENT_MODIFICATION`; 400 `BAD_REQUEST` (unknown `reason`, no body) | `ConversationController:95-100`; `ConversationService:122-157`; `IT/api/HardeningIT.java:214-235` |

Behaviour notes:

- **V1 check order:** field limits, then queue (404), then existence (409) (`ConversationService:51-67`). The existence check is a read followed by an insert, so two concurrent creates can end as one 201 plus one 409 `CONFLICT` instead of `CONVERSATION_EXISTS` (inference from `:65-72` and `ApiExceptionHandler:80-84`). Treat both 409 codes as "already exists" and confirm with V2.
- **V1 time fields:** `createdAt` and `enqueuedAt` default to the engine's now and may be back-dated but not more than 60 s ahead (`:59-61`). The queue order is `enqueued_at, id`. **Recommendation:** send both, equal to the platform conversation's creation time, so FIFO order matches the platform even when the create call is retried minutes later.
- **V3 works from any live state**, including `QUEUED`: the conversation is resolved without ever being assigned (`IT/api/HardeningIT.java:137-139`, 200). It closes the active assignment as `COMPLETED` in the same transaction and needs no feature flag. A second call is 409.
- **V4 reason values:** `TRANSFER` (default when `reason` is null), `REJECT`, `TIMEOUT`, `REASSIGN`, `REOPEN` (`ConversationService:26-28`; `ConversationController:98`). `REOPEN` is allowed only when the conversation is `RESOLVED`; every other reason only for `ASSIGNED`, `ACCEPTED` or `ACTIVE` (a waiting conversation may only be moved to a different queue with `TRANSFER` and `targetQueueId`) (`:137-147`). Otherwise 409 `INVALID_REQUEUE`. A `CANCELLED` conversation is always 409 `CONVERSATION_CLOSED` (`:132-134`).
- **V4 exclusion:** `excludePreviousAgent` null or true adds `assignedAgentId` to `excludedAgentIds` (`ConversationController:99`; `ConversationService:149-152`). After a resolve, `assignedAgentId` is still set, so `true` would exclude the agent who just handled the conversation, and a single-agent tenant could never receive the reopened conversation. The platform sends `false`. **Nothing removes an id from `excludedAgentIds`** (not found; it is written at V1 and extended by requeue and reassign only).
- The guard order in V4 is feature flag, then target-queue lookup, then conversation lookup (`:123-127`): with the flag off every call is 403 even for a missing conversation.
- The conversation gets a new `enqueuedAt` on every requeue (`:154`), which puts it at the back of the FIFO order.

### 4.4 Allocation (roles `operator`)

| ID | Call | Min role | Headers beyond the standard two | Request body | Success | Errors (status `code`) | Source |
|---|---|---|---|---|---|---|---|
| A1 | `POST /api/v1/allocations/dispatch` | `operator` | `Idempotency-Key` | `{"conversationId": "<uuid>"}` | 200 `AllocationDecision` for every outcome | 400 `QUEUE_OR_CONVERSATION_REQUIRED`, `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_TOO_LONG`; 404 `CONVERSATION_NOT_FOUND`; 409 `NO_ACTIVE_POLICY`, `POLICY_NOT_ACTIVE`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_RACE`, `OVERFLOW_QUEUE_MISSING`; 403 `FEATURE_DISABLED` only if `claimingAgentId` is sent | `AllocationController:50-54`; `Dispatcher:124-129, 263-308, 434-451` |
| A2 | `POST /api/v1/allocations/claim` | `operator` | `Idempotency-Key` | `{"conversationId": "<uuid>", "agentId": "<agent>"}` | 200 `AllocationDecision` | 400 `CLAIM_FIELDS_REQUIRED`; 403 `FEATURE_DISABLED` (`allocation.ops.claim`); the A1 errors | `AllocationController:56-63`; `Dispatcher:125-127` |
| A3 | `POST /api/v1/conversations/{id}/reassign` | `operator` | `Idempotency-Key` | none | 200 `AllocationDecision` | 403 `FEATURE_DISABLED` (`allocation.ops.reassignment`); 404 `CONVERSATION_NOT_FOUND`; 409 `NOT_ASSIGNED`, `NO_ACTIVE_POLICY`, `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_RACE`; 400 `IDEMPOTENCY_KEY_*` | `ConversationController:102-107`; `Dispatcher:132-144` |

Behaviour notes:

- **A1 body.** `AllocationRequestBody(queueId, conversationId, claimingAgentId, policyId, policyVersion)` (`AllocationController:25`). With `conversationId` present, `queueId` is ignored and the conversation's own queue and its active policy are used (`DispatchCommand:3-11`). Do not send `claimingAgentId` (it turns the call into a claim and requires the claim flag, `Dispatcher:125-127`). `policyId` and `policyVersion` are accepted only if they name the active published version covering the queue (409 `POLICY_NOT_ACTIVE`, `Dispatcher:435-446`); omit them.
- **A1 outcomes.** What each outcome means and how it should be handled is in sections 2.4 and 7.2. The `not QUEUED` case is section 5.4.
- **A2 outcomes.** `ASSIGNED` with `agentStrategy` `claim` (`IT/application/DispatcherIT.java:125-128`); `CLAIM_REJECTED` when the agent fails a filter (offline, stale, capacity, excluded, disabled) with the failing code as second reason code (`IT/application/DispatcherIT.java:137-143`); `REJECTED_INVALID_INPUT` with `CONVERSATION_NOT_ALLOCATABLE` (not `QUEUED`, including a second claim) or `AGENT_NOT_FOUND` (the agent is not in the conversation's queue, because the snapshot holds only that queue's agents, `SnapshotBuilder:38`; so the `queue-membership` filter is unreachable for claims, inference). A claim works under an `AUTO` policy too (`DefaultAllocationEvaluator:225-229`).
- **A3 sequence** in one transaction: row lock, `NOT_ASSIGNED` unless `ASSIGNED`/`ACCEPTED`/`ACTIVE`, release the assignment as `REASSIGNED`, add the previous agent to `excludedAgentIds` (always `true`, `Dispatcher:141`), put the conversation back in `QUEUED`, then evaluate again (`:132-144`). The decision can therefore be `ASSIGNED` to another agent, any `QUEUED_*` (the release stays committed), or `CONFLICT_RETRYABLE` (nothing happened, the release rolled back too).
- **Feature guard order in A2/A3:** role, tenant, header binding, then flag (403) before any idempotency lookup (`Dispatcher:125-127, 133`).

### 4.5 Health

| ID | Call | Min role | Success | Notes | Source |
|---|---|---|---|---|---|
| H1 | `GET /api/v1/health` | `viewer` | 200 `{"status": "UP" or "DEGRADED", "database": "UP" or "DOWN", "time": "<iso>", "version": "0.1.0-SNAPSHOT"}` | No tenant header needed. **Returns 200 even when the database is down** (`status` `DEGRADED`), so `healthy()` must check `status == "UP"`. `time` is the engine clock; usable to detect clock skew. | `OverviewController:46-56`; `LocalDevAuthInterceptor:55` |
| H2 | `GET /actuator/health` | none | 200 `{"status": "UP"}` | Spring Boot Actuator, exposure `health,info,metrics`, probes enabled so `/actuator/health/liveness` and `/readiness` exist (`res/application.yaml:24-32`; documented in `docs/operations/local-development.md:29`). The body shape and a 503 when the database is down are Spring Boot defaults and were not exercised here (inference). Suited to the container health check. | `res/application.yaml:24-32` |

`/actuator/prometheus` is **not exposed**: the include list is `health,info,metrics` (`res/application.yaml:28`) and neither POM declares a Prometheus registry (not found). Engine metrics are readable as JSON at `/actuator/metrics/allocation.dispatch`, `.retries` and `.conflicts` (documented, `docs/operations/configuration-reference.md`). Relevant to Plan 05.

### 4.6 Optional reads (not in the brief's list, useful to Plan 04)

| ID | Call | Min role | Success | Why |
|---|---|---|---|---|
| O1 | `GET /api/v1/conversations/{id}/assignments` | `viewer` | 200 `[AssignmentRecord]` ordered by `created_at`; the entry with status `ASSIGNED`/`ACCEPTED`/`ACTIVE` is current and carries `decisionId` and `agentId`; 404 `CONVERSATION_NOT_FOUND` | the only way to learn `decisionId` for an assignment the sweep made (`ConversationController:59-63`; `ConversationRepository:91-94`) |
| O2 | `GET /api/v1/conversations?queueId=&status=&limit=` | `viewer` | 200 `[ConversationSnapshot]` ordered by `enqueued_at, id`; `limit` clamped to 1..1000, default 200; no offset or cursor; unknown `status` is 400 `BAD_REQUEST` | bulk reconcile of many `QUEUED` conversations in one call (`ConversationController:41-45`) |
| O3 | `POST /api/v1/allocations/sweep` body `{"queueId": "<q>", "maxAssignments": 20}` | `operator` | 200 `[AllocationDecision]` | runs one sweep for a queue now instead of waiting up to 5 s; `queueId` required (400 `QUEUE_REQUIRED`); `maxAssignments` clamped to 1..500 (`AllocationController:65-70`) |
| O4 | `GET /api/v1/allocations/decisions?conversationId=` | `viewer` | 200 `[AllocationDecision]` | audit trail for support tooling; without `conversationId` the last `limit` (default 50, max 500) of the tenant (`AllocationController:79-86`) |

`accept`, `activate` and `DELETE /conversations/{id}` (cancel) exist but are not part of the planned flow.

### 4.7 Wire shapes

JSON field names are the Java record component names. Instants are ISO-8601 strings; `Set`s are JSON arrays.

- `QueueRecord`: `id, tenantId, name, description` (`persistence/QueueRecord.java`).
- `AgentRecord`: `id, tenantId, displayName, enabled, status, lastHeartbeatAt, maxActiveAssignments, skills[], languages[], totalAssignedCount, lastAssignedAt, memberships[{queueId, maxActiveInQueue}]` (`persistence/AgentRecord.java:13-25`).
- `AgentRequest` (S9): `id, displayName, enabled, status, maxActiveAssignments, skills[], languages[], memberships[{queueId, maxActiveInQueue}]` (`CatalogController:37-38`).
- `PresenceRequest` (P1): `status, heartbeatAt` (`CatalogController:41`).
- `CreateConversationRequest` (V1): `conversationId, queueId, priority (int, 0 if omitted), requiredSkills[], language, slaDueAt, excludedAgentIds[], createdAt, enqueuedAt` (`application/CreateConversationRequest.java:6-16`).
- `ConversationSnapshot`: `conversationId, tenantId, queueId, priority, createdAt, enqueuedAt, requiredSkills[], language, status, slaDueAt, excludedAgentIds[], assignedAgentId` (`core/domain/ConversationSnapshot.java:18-30`).
- `RequeueRequest` (V4): `reason, excludePreviousAgent, targetQueueId` (`ConversationController:28`).
- `AllocationDecision` (A1 to A3): `decisionId, outcome, tenantId, conversationId, queueId, agentId, policyId, policyVersion, policyHash, conversationStrategy, agentStrategy, evaluatedAt, mode, fallback, reasonCodes[], queueOrder[], candidates[], candidatesTruncated, capacityBefore{}, newRoundRobinCursor, explanation, featureSetHash` (`core/domain/AllocationDecision.java:19-41`; example `docs/api/examples/decision-assigned.json`).
- `AssignmentRecord` (O1): `id, tenantId, conversationId, agentId, queueId, status, decisionId, createdAt, updatedAt` (`persistence/AssignmentRecord.java`).
- `PolicyRecord` (S4 to S6): `id, tenantId, name, state (DRAFT, PUBLISHED or DISABLED), draft, draftUpdatedAt, activeVersion, createdAt, updatedAt`; `VersionRecord` (S7): `policyId, version, definition, hash, publishedAt, publishedBy, rolledBackFrom` (`PolicyRepository:16-24`).
- Error: `{status, code, message, issues[], correlationId}`.

Example (A1):

```
POST /api/v1/allocations/dispatch
X-Tenant-Id: <tenant>
X-Role: operator
Idempotency-Key: dispatch:<conversationId>:1
Content-Type: application/json

{"conversationId": "<conversationId>"}
```

Response 200 (abridged; a full engine-produced sample is `docs/api/examples/decision-assigned.json`):

```
{"decisionId": "09d85d9b-bfff-39fa-b065-76aa29638908", "outcome": "ASSIGNED", "conversationId": "...", "agentId": "...",
 "reasonCodes": ["SELECTED_BY_RANK"], "explanation": "ASSIGNED: conversation ...", ...}
```

### 4.8 The typed boundary: `AllocationClient` and `AllocationSeeder`

The method list is the one in overview section 5, glossary entry `assignment` [04]. This table maps every method to the calls above.

| Method (overview section 5) | Engine call(s) | Mapping rule |
|---|---|---|
| `Optional<AllocationConversationState> findConversation(tenantId, conversationId)` | V2 | 200 gives `AllocationConversationState(status, assignedAgentId)`; 404 `CONVERSATION_NOT_FOUND` gives `Optional.empty()` (a 404 `TENANT_NOT_FOUND` is a different problem and must not become empty) |
| `void createConversation(tenantId, conversationId, queueId, createdAt)` | V1 | 201 and 409 `CONVERSATION_EXISTS`/`CONFLICT` are both success; also send `enqueuedAt = createdAt` (section 4.3) |
| `AllocationResult dispatch(tenantId, idempotencyKey, conversationId)` | A1 | `AllocationResult(outcome, agentId, decisionId, explanation, reasonCodes)` from the decision; never throw on a non-`ASSIGNED` outcome |
| `AllocationResult claim(tenantId, idempotencyKey, conversationId, agentId)` | A2 | same mapping |
| `AllocationResult reassign(tenantId, idempotencyKey, conversationId)` | A3 | same mapping; 409 `NOT_ASSIGNED` becomes `AllocationRejectedException(409, "NOT_ASSIGNED")` |
| `void resolve(tenantId, conversationId)` | V3 | 409 `CONVERSATION_CLOSED` is reported as a rejected exception; the worker decides after a V2 read (section 7.2) |
| `void requeueReopen(tenantId, conversationId)` | V4 with `{"reason": "REOPEN", "excludePreviousAgent": false}` | 409 `INVALID_REQUEUE` is reported as a rejected exception; same rule |
| `AgentPresenceView presence(tenantId, agentId, Availability status, Instant heartbeatAt)` | P1 | `Availability` names equal the engine's `AvailabilityStatus` (`ONLINE`, `AWAY`, `OFFLINE`); response mapped from `AgentRecord` |
| `List<AgentPresenceView> agents(tenantId)` | P2 | map each `AgentRecord` |
| `boolean healthy()` | H1 (and optionally H2) | true only for HTTP 200 with `status == "UP"`; any I/O failure is false |
| `AllocationSeeder.ensureTenant(tenantId, tenantName, queueId, agents)` | S1, S3, S4/S6/S7, S8/S9, optionally S10 | sequence in section 7.3 |

Exception mapping (types from overview section 5): connect, read and timeout failures, HTTP 502/503/504 and HTTP 500 `INTERNAL_ERROR` become `AllocationUnavailableException`; every other 4xx becomes `AllocationRejectedException(status, code)` using the `status` and `code` of the error body. A 200 with a non-`ASSIGNED` outcome is a normal `AllocationResult`.

## 5. Dispatch transaction and idempotency (Step 3)

### 5.1 The transaction

`Dispatcher.execute` (`Dispatcher:263-405`) runs once per attempt inside one `TransactionTemplate` transaction at the database default isolation, READ COMMITTED (documented in `docs/architecture/concurrency-and-consistency.md`; no code sets another level, inference):

1. Resolve the policy before any lock: the queue's active, published, enabled policy, else 409 `NO_ACTIVE_POLICY` (`:434-451`; `PolicyRepository:103-110`).
2. Lock the conversation with `select … where status = 'QUEUED' for update skip locked` (`ConversationRepository:56-58`). No row: section 5.4.
3. If the locked conversation is now in another queue than the policy's, start again (`RetryableConflict`) (`:313-319`).
4. Lock the per-queue round-robin cursor row when the ranking uses it (`:324-330`).
5. Build the snapshot from the database and evaluate with the pure core (`:331-333`).
6. Lock the winning agent `FOR UPDATE`, recount its load, re-verify every eligibility filter against the locked row; if it changed, re-rank, at most three checks (`:341-371`).
7. Write: `update conversation … where status = 'QUEUED'` (0 rows is a retryable conflict), insert the assignment, bump the agent's counters, upsert the cursor (`:381-392`), or apply the `move-to-queue` fallback (`:393-402`).
8. Insert the idempotency record, then the decision audit row (`:425-432`). A duplicate key rolls the transaction back and returns the stored response (`:238-243`).
9. Commit. **No notification is sent** (section 7.1).

Reassign wraps steps 1 to 9 in one outer transaction that first locks the conversation, checks `NOT_ASSIGNED` and releases the old assignment (`:132-144`).

### 5.2 Idempotency

- **Scope and storage.** One row per `(tenant_id, key)` in `idempotency_record(tenant_id, key, request_hash, response jsonb)` (`res/db/migration/V1__schema.sql:176-183`; `IdempotencyRepository:21-36`). The platform's keys are therefore unique per tenant, not globally.
- **Request hash.** SHA-256 of `operation|queueId|conversationId|claimingAgentId|policyId|policyVersion` where `operation` is `dispatch` (used by dispatch **and** claim) or `reassign` (`Dispatcher:509-516`). The key itself is not in the hash. A claim differs from a dispatch of the same conversation through `claimingAgentId`.
- **Replay.** Before any transaction, a stored record with the same key and the same hash returns the stored decision unchanged (`:227-232, 476-483`). The stored decision is returned **whatever the conversation looks like now**. Example: dispatch with key K returned `ASSIGNED` to agent A; the conversation is later resolved and reopened; dispatch with K returns the old `ASSIGNED` to A even though the conversation is `QUEUED` and unassigned.
- **Different request, same key.** Same key with a different hash: 409 `IDEMPOTENCY_KEY_REUSED` (`:476-480`). It is thrown before any work, nothing is written.
- **What is stored.** Every decision that reaches `persist` (all outcomes except `CONFLICT_RETRYABLE`, section 2.4). **Not stored:** anything that ends in an `ApiException` (400, 403, 404, 409 including `NO_ACTIVE_POLICY` and `NOT_ASSIGNED`): the transaction rolls back and nothing is written (`IT/application/DispatcherIT.java:106-114` for `NO_ACTIVE_POLICY`). Retrying the same key after such an error is therefore safe and runs fresh.
- **Decision id.** `UUID.nameUUIDFromBytes("<len(tenant)>:<tenant>:<key>")` (`:472-474`; `core/engine/DefaultAllocationEvaluator.java:61-63`). The same (tenant, key) always gives the same `decisionId`. Sweeps use a random id because they have no key (`:267`).
- **Concurrent twins.** Two requests with the same key at the same moment: the insert of the idempotency record blocks on the primary key and the loser returns the stored response (`:238-243`). If the winner has not committed when the loser looks, the loser is 409 `IDEMPOTENCY_RACE` ("retry") (`:243`). Same-key twins of an *untargeted* dispatch can deadlock on an agent row; PostgreSQL breaks it and the retry returns the stored response (documented, `docs/architecture/concurrency-and-consistency.md`, "Known limits").
- **Retention.** None: `idempotency_record` and `allocation_decision` grow without a purge (documented: "no retention job yet", same file; `HANDOVER.md` section 9). Plan 05 must schedule one or accept growth. Not found in code.

### 5.3 Retries and `CONFLICT_RETRYABLE`

- A retry loop of **three attempts in total** (`MAX_ATTEMPTS = 3`, `Dispatcher:72, 235`): the original plus two retries. The engine's own documents say "retried up to 3 times"; the code makes three attempts, then gives up (section 10).
- What triggers a retry: a lost race inside the transaction (`RetryableConflict`: conversation locked by another worker, status changed meanwhile, moved queue, re-ranked winner locked by another dispatch, eligibility changing under the lock) and Spring's `PessimisticLockingFailureException` family (deadlock `40P01`, serialization failure `40001`, lock timeout) (`:244-252`). Between attempts the thread sleeps `10 + random(0 .. 20 × (attempt + 1))` ms (`:501-507`).
- After the last failed attempt the response is a **200** `AllocationDecision` with outcome `CONFLICT_RETRYABLE`, `reasonCodes: ["INVALID_INPUT"]`, no agent, `policyId` `unknown` and an explanation naming the three attempts (`:254-256, 485-495`). It is built in memory and **not stored**; a retry with the same key runs a fresh attempt. The counter `allocation.dispatch.conflicts` increments (`:120`).
- Measured frequency: about 0.3 % of saturated sweeps give up (documented, `HANDOVER.md` section 9; `docs/testing/performance-baseline.md`, not re-run). For one platform tenant that dispatches one conversation at a time this should be rare.
- There is no server-side time limit on the first agent-row lock wait (documented, `docs/architecture/concurrency-and-consistency.md`, "Known limits"). **Recommendation:** the HTTP client needs its own timeout, and a timed-out call is retried with the same key, which is safe because of 5.2.

### 5.4 Dispatching a conversation that is no longer `QUEUED`

Read from `Dispatcher:273-292` and `core/engine/DefaultAllocationEvaluator:109-119`.

1. `conversations.find` is a plain read. A missing row is 404 `CONVERSATION_NOT_FOUND` (`:274-275`).
2. `lockQueued` (`… and status = 'QUEUED' … skip locked`) returns no row (`:279-280`).
3. If the pre-read status was `QUEUED`, another worker holds the row: `RetryableConflict`, up to three attempts, then `CONFLICT_RETRYABLE` (`:284-286`).
4. Otherwise (`ASSIGNED`, `ACCEPTED`, `ACTIVE`, `RESOLVED`, `CANCELLED`) the evaluator runs on a snapshot that includes the conversation and answers **`REJECTED_INVALID_INPUT` with reason code `CONVERSATION_NOT_ALLOCATABLE`** ("conversation X is ASSIGNED, only QUEUED can be allocated"). It is persisted: decision row and idempotency record, **nothing else** (`:287-291`). The HTTP status is **200**.
5. Observed by test: a second claim on an already claimed conversation gives exactly that outcome and leaves one assignment (`IT/application/DispatcherIT.java:130-133`).
6. The test is on the conversation, not the agent: a claim by an unknown agent on a non-`QUEUED` conversation also gives `CONVERSATION_NOT_ALLOCATABLE`, because the target check (`DefaultAllocationEvaluator:116`) runs before the claimer lookup (`:149-153`).
7. The policy is resolved first (`:277`), so with no active policy the same call is 409 `NO_ACTIVE_POLICY`.

**Plan 04 relies on this.** When the sweep, or a previous dispatch whose response was lost, has already assigned the conversation, `dispatch` returns `REJECTED_INVALID_INPUT` / `CONVERSATION_NOT_ALLOCATABLE`. That is the signal to `GET` the conversation and adopt its `status` and `assignedAgentId`. It is a reconcile path, not an error, and it must not be retried.

### 5.5 What this means for the key scheme (Plan 04 decides)

- A key is a permanent answer to one logical request. Build it from the **operation, the conversation id and a generation counter** that the platform increments each time it starts a new allocation cycle (first assignment, reopen, reassign). **Recommendation:** `dispatch:<conversationId>:<generation>` and `reassign:<conversationId>:<generation>`. With a 36-character UUID that is under 60 characters; the limit is 128 after trimming.
- Retrying the *same* logical request after a timeout or a `CONFLICT_RETRYABLE` reuses the same key. Re-dispatching after a queued outcome uses a new key, but the sweep makes that unnecessary: poll instead (section 7.2, F8).
- A dispatch key and a reassign key must never coincide: the hash includes the operation, so a shared key is 409 `IDEMPOTENCY_KEY_REUSED`.
- Every new key adds one `idempotency_record` row and one `allocation_decision` row. Do not mint a key per poll.
- A claim key must not embed an agent id of up to 128 characters. Use a hash or a generation counter.

## 6. Scheduler, feature levels and configuration (Step 4)

Defaults and variable names are in the [inventory](workspace-inventory.md) section 6.1. This section adds behaviour.

### 6.1 The scheduler (`DispatchScheduler`)

- Present only when `allocation.dispatcher.enabled=true` (`ALLOCATION_DISPATCHER_ENABLED`, default true) (`DispatchScheduler:17`; `res/application.yaml:36`). `@EnableScheduling` is on the application class (`AllocationServiceApplication:8`).
- `@Scheduled(fixedDelay = allocation.dispatcher.delay, initialDelay = same)`; the default is `5s` (`ALLOCATION_DISPATCHER_DELAY`) (`DispatchScheduler:32`; `res/application.yaml:37`). Fixed *delay*: 5 s after the previous sweep finished.
- One thread. Every tick loops over **all tenants and all their queues** and calls `Dispatcher.sweep(tenant, queue, batchSize)` with `ALLOCATION_DISPATCHER_BATCH` default 20 (`DispatchScheduler:33-47`; `res/application.yaml:38`). A failure in one queue is logged and the loop goes on. The cost per tick grows with tenants times queues, on a single thread (documented in `docs/architecture/concurrency-and-consistency.md`, "Known limits"; relevant to Plan 07).
- `sweep` (`Dispatcher:162-196`): returns at once for a queue with no active policy or a `MANUAL` policy (`:164-167`); reads the queue order once (at most 500 waiting conversations, section 2.2); dispatches each waiting conversation in order with a **null idempotency key** and `skipIfTaken` (`:172-179`); skips a conversation held by another worker or no longer waiting without retries or audit rows (`:185-187`); does not rewrite a queued outcome identical to the conversation's last stored one (`:374-378`); stops the queue at the first `QUEUED_CAPACITY_EXHAUSTED` or `QUEUED_NO_AVAILABLE_AGENT` (`:191-193`); stops after `batchSize` assignments (`:173-175`).
- Consequences: an agent who comes online or frees a slot is picked up within about 5 s plus processing time; at most 20 assignments per queue per tick; a conversation created by the platform is assigned without any dispatch call; a sweep emits nothing (section 7.1). `dispatch` is an accelerator and an explicit result source, not a requirement (inference). `POST /allocations/sweep` (O3) runs the same code on demand.

### 6.2 Feature levels

- Default level `basic` (`ALLOCATION_FEATURE_LEVEL`, `res/application.yaml:42`); levels are cumulative bundles of 18 flags: `basic`, `balanced`, `routing`, `operations`, `workbench` (alias `full`) (documented: `docs/architecture/feature-levels-and-packaging.md`; flags in `core/feature/Features.java:10-47`).
- **The platform needs `operations`** (overview D8): `allocation.ops.claim` gates `POST /allocations/claim` and any dispatch with `claimingAgentId`; `allocation.ops.reassignment` gates `POST /conversations/{id}/reassign` and `POST /conversations/{id}/requeue` (`Dispatcher:125-127, 133`; `ConversationService:123`; flags `Features.java:43-45`). Off means 403 `FEATURE_DISABLED` with the message "Feature <key> is off for tenant <tenant>." (`FeatureGuard:19-23`). Plain dispatch, resolve, create and presence need no flag.
- Alternative to a deployment-wide level: a per-tenant override `PUT /api/v1/features/{key}` with `{"enabled": true}` (role `admin`), allowed while `ALLOCATION_FEATURE_TENANT_OVERRIDES` is true (default) (`FeatureController:34-39`; `res/application.yaml:43`). Flags are cached per tenant for up to `ALLOCATION_FEATURE_CACHE_TTL` (5 s). **Recommendation:** use the deployment level and let the seeder assert it with S10.
- Policies published at the platform's level may use every strategy up to `operations` (section 2.6).

### 6.3 Demo seeding and the default tenant

- `DemoSeeder` runs at start when `allocation.seed-demo=true` (`ALLOCATION_SEED_DEMO`, default **true**) (`DemoSeeder:34`; `res/application.yaml:39`). It creates tenant `demo-tenant`, a tenant override for every feature flag, queues `q-support` and `q-overflow`, five demo agents, the sample policies (round-robin published), scenarios and regression cases, and four waiting conversations; each step is guarded by "only if empty" (`DemoSeeder:66-114`). A 30 s scheduled job keeps demo agents' heartbeats fresh (`:119-125`). It touches only `allocation.default-tenant` (`:62, 67`).
- **For the platform set `ALLOCATION_SEED_DEMO=false`.** (a) The scheduler sweeps every tenant, so the demo tenant would be swept alongside real ones (`DispatchScheduler:34`). (b) A request without `X-Tenant-Id` silently becomes `demo-tenant` (`LocalDevAuthInterceptor:40`; `res/application.yaml:46`; no environment variable changes it). With seeding on, a client bug writes into demo data; with seeding off, the same bug is a 404 `TENANT_NOT_FOUND` on every non-catalogue endpoint and an empty list on `GET /queues`.

### 6.4 CORS, bind address, UI location, limits

- **CORS** is configured for `/api/**`: origins from `ALLOCATION_CORS_ORIGINS` (default `http://localhost:5173`), methods `GET, POST, PUT, DELETE, OPTIONS`, request headers `Content-Type, X-Tenant-Id, X-Role, X-Correlation-Id, Idempotency-Key`, exposed header `X-Correlation-Id`, no credentials, max age 3600 s (`WebConfig:32-41`). It matters only to browsers. The platform backend calls server to server, so leave it at the default.
- **Bind address:** `server.address = ${ALLOCATION_BIND_ADDRESS:127.0.0.1}` (`res/application.yaml:22`). Inside a container it must be `0.0.0.0`, otherwise a published port cannot reach it (inference; the inventory already records this).
- **UI static location:** `spring.web.resources.static-locations = classpath:/static/,${ALLOCATION_UI_DIST:file:../../ui/dist/}` (`res/application.yaml:17`), a path relative to the working directory. The jar carries no `static/` directory (`res/` holds only `application.yaml` and `db/`), so in a container nothing is served at `/`. The platform does not need the workbench. Spring's behaviour for a missing directory was not exercised (inference: it serves nothing). `/swagger-ui.html` and `/v3/api-docs.yaml` come from springdoc (`backend/service/pom.xml:58`).
- **Limits:** request body 5 MiB; ids 128 characters; free text 2000 characters; lists 200 entries; priority -1000..1000 (`Limits:20-30`; `RequestSizeFilter`).

### 6.5 Container environment for Plan 04 (recommendation)

Values the platform compose would set for the allocation container. Host-run values (ports 8081 and 5433) are in the inventory section 6.1.

| Variable | Value | Reason |
|---|---|---|
| `ALLOCATION_DB_URL` | `jdbc:postgresql://allocation-postgres:5432/allocation` | the container network uses the database's internal port 5432; the host mapping 5433 is only for tools on the host |
| `ALLOCATION_DB_USER`, `ALLOCATION_DB_PASSWORD` | from the platform `.env`, fake local values | `res/application.yaml:6-7` |
| `ALLOCATION_PORT` | `8080` inside the container, published as host 8081 (or `8081` inside) | removes the 8080 collision; either works |
| `ALLOCATION_BIND_ADDRESS` | `0.0.0.0` | section 6.4 |
| `ALLOCATION_FEATURE_LEVEL` | `operations` | section 6.2 |
| `ALLOCATION_SEED_DEMO` | `false` | section 6.3 |
| `ALLOCATION_DISPATCHER_ENABLED`, `_DELAY`, `_BATCH` | `true`, `5s`, `20` (defaults) | section 6.1 |
| `ALLOCATION_UI_DIST` | unset | no workbench in the image |
| `ALLOCATION_CORS_ORIGINS` | default | unused by server-to-server calls |

The service starts Flyway migrations V1 to V5 itself (`res/application.yaml:8-9`; `res/db/migration/`), so the container must wait for the database to be healthy.

## 7. The integration decision (Step 5)

### 7.1 Decision and its basis in the code

| Overview decision | Basis in the engine | Consequence |
|---|---|---|
| Separate container with its own PostgreSQL (D8) | The engine owns its schema and runs its own Flyway on start (`res/application.yaml:8-9`). No shared tables, no shared transaction. | The conversation row on the platform side is the durable request; no distributed transaction exists. |
| HTTP gateway `assignment.AllocationClient` (D8) | REST only. **Not found:** a Java client library, a published OpenAPI usable for generation (section 10). | Hand-write the client from section 4. |
| Projection on the platform's `conversations` row (D8) | `GET /conversations/{id}` returns `status` and `assignedAgentId` (V2). `decisionId` only via O1. | `assignment_state`, `assigned_agent_id`, `assignment_reason` come from V2/A1; `assignment_decision_id` is complete only if the worker also reads O1 for sweep assignments. |
| Worker-driven state machine `REQUESTED → dispatch`, `QUEUED → poll`, `RELEASE_PENDING → resolve`, `REOPEN_PENDING → requeue REOPEN` (D8) | Every engine call is synchronous. Dispatch, claim and reassign are idempotent by key; resolve and requeue are not and need a reconcile read. | See the failure matrix. |
| Poll-based reconciliation for sweep assignments | The scheduler assigns every 5 s and sends nothing (section 6.1). | The worker polls V2 for `QUEUED` conversations at an interval of about the sweep delay or longer. |
| No webhook | **Not found:** any outbound call from the engine. `grep` for `RestClient`, `WebClient`, `RestTemplate`, `HttpClient` and `java.net.http` over `backend/service/src/main` and `backend/core/src/main` finds nothing, and neither does `grep` for `ApplicationEventPublisher`, `@EventListener` or `publishEvent` (so the "in-process event" of ADR-009 is not implemented). `service/pom.xml` has no HTTP-client starter; `Dispatcher` step 9 states "Notifications (none in v1 beyond logs/metrics)" (`docs/architecture/concurrency-and-consistency.md`). The only `afterCommit` hook in the code increments metrics (`Dispatcher:408-423`). The `AllocationEventListener` SPI mentioned in `docs/architecture/feature-levels-and-packaging.md` is plan B and does not exist (`grep AllocationEventListener` over `backend` finds nothing). | Polling is the only mechanism today. |
| Presence forwarded (D9) | `POST /agents/{id}/presence` (P1); the engine cannot observe presence. | The dashboard heartbeat (30 s) is forwarded as-is; a missed forward is repaired by the next beat. Two missed beats (60 s) make the agent stale for allocation. |
| Level `operations` (D8) | Section 6.2. | Set at deployment. |

**Upgrade path (no webhook now).** An outbound notification could be added later as an additive change in the engine, backward compatible because it is off unless configured: register a `TransactionSynchronization.afterCommit` in `Dispatcher.persist` (one place that covers dispatch, reassign and sweep, `Dispatcher:425-432`) and in the `ConversationService` transitions (resolve, requeue, accept, activate, cancel), and send the event to a configured URL. The brief calls this "the allocation afterCommit hook"; **no such notification hook exists today**, only the metrics one (section 10). This is a recommendation for a later plan, not part of Plan 04.

### 7.2 Failure matrix

"Same key" means retry the same call with the same `Idempotency-Key`. "Reconcile" means `GET` the conversation (V2) and adopt its state. Actions are recommendations for the `AssignmentWorker`; the signals are observed.

| # | Situation | Signal from the engine | Class | Platform action |
|---|---|---|---|---|
| F1 | Allocation service down, restarting or unreachable | connect refused, timeout, DNS failure, HTTP 502/503/504 or 500 `INTERNAL_ERROR`; H1 returns anything but 200 with `status` `UP` | transient | `AllocationUnavailableException`; leave the conversation's `assignment_state` unchanged; retry with back-off, **same key** (safe even if the first request committed: the stored decision is replayed, `Dispatcher:227-232`). Chat keeps working because the conversation row is the durable request. `healthy()` false feeds the platform status endpoint. A database-only outage shows as H1 200 with `database` `DOWN`; all other calls are 500. Presence forwards are dropped; the next heartbeat repairs it. |
| F2 | Tenant missing (never seeded, or the engine database was reset) | 404 `TENANT_NOT_FOUND` on any non-catalogue call; S2 returns `[]`; P1 returns 404 `AGENT_NOT_FOUND` | needs repair | run `AllocationSeeder.ensureTenant` (all steps idempotent, section 7.3) and retry. If conversations vanished with the database, V2 gives 404 `CONVERSATION_NOT_FOUND`: create them again and restart their cycle; raise an alert because projections no longer match the engine. |
| F3 | Queue missing (inbox created after tenant seeding) | 404 `QUEUE_NOT_FOUND` on V1, S9 or V4 `targetQueueId` | needs repair | `POST /queues` for `inboxes.allocation_queue_id` (S3, upsert), add the queue to a policy (F3b) and to the agents' memberships, then retry. |
| F3b | No policy covers the queue | A1/A3 409 `NO_ACTIVE_POLICY`; the sweep skips the queue silently (`Dispatcher:164-167`) | needs repair, alert | seed and publish a policy for the queue (7.3). Not retryable until fixed. |
| F4 | `CONVERSATION_EXISTS` | V1 409 `CONVERSATION_EXISTS`, or 409 `CONFLICT` after a lost race | success | treat as created; reconcile if the state matters. |
| F5 | `NOT_ASSIGNED` | A3 409: the conversation is `QUEUED`, `RESOLVED` or `CANCELLED` | reconcile | do not retry. Reconcile: `QUEUED` means a release already happened, go to the poll step; `RESOLVED` or `CANCELLED` means the platform and engine disagree, alert. |
| F6 | `INVALID_REQUEUE` | V4 409 | reconcile | if V2 shows `QUEUED`, `ASSIGNED`, `ACCEPTED` or `ACTIVE`, the reopen already happened (earlier call or lost response): adopt that state. A `CANCELLED` conversation answers `CONVERSATION_CLOSED` instead: alert. Never retry blindly. |
| F6b | `CONVERSATION_CLOSED` on resolve | V3 409 | success or alert | V2 shows `RESOLVED`: release complete. `CANCELLED` or any other: alert. |
| F6c | Gated operation off | 403 `FEATURE_DISABLED` on V4, A2 or A3 | configuration, alert | stop; check S10; set level `operations`. Not retryable. |
| F7 | `CONFLICT_RETRYABLE` outcome | A1/A2/A3 200 with that outcome | transient | same key after a short pause (the engine already made three attempts with a 10 to about 70 ms jitter between them). After a few repeats stop dispatching and poll: the sweep serialises contention. |
| F8 | `QUEUED_NO_ELIGIBLE_AGENT`, `QUEUED_CAPACITY_EXHAUSTED`, `QUEUED_NO_AVAILABLE_AGENT` | A1/A3 200, stored | waiting | set the projection to `QUEUED`; poll V2 every few seconds (at least the 5 s sweep delay) until `status` is `ASSIGNED`; **do not re-dispatch with the same key** (it would replay the queued decision). `reasonCodes` explain why (`NO_AVAILABLE_CANDIDATE`, `ALL_CANDIDATES_AT_CAPACITY`, `NO_ELIGIBLE_CANDIDATE`). A conversation whose only eligible agent is in its `excludedAgentIds` waits for ever (section 4.3): surface it. |
| F9 | `QUEUED_FEATURE_DISABLED`, `SKIPPED_POLICY_DISABLED`, `FAILED_INTERNAL` | A1 200, stored | configuration or engine fault, alert | do not retry the same key (replayed). Fix the policy or flags; use a new key after the fix. |
| F10 | `REJECTED_INVALID_INPUT` | A1/A2/A3 200, stored | reconcile or bug | `CONVERSATION_NOT_ALLOCATABLE`: reconcile via V2 (section 5.4); `AGENT_NOT_FOUND` on a claim: the agent is not in the conversation's queue or does not exist, a seeding or membership bug; other codes: bug. Never retry the same key. |
| F11 | `CLAIM_REJECTED` | A2 200, stored | business | the agent fails a hard filter (`reasonCodes[1]`, for example `AGENT_OFFLINE`, `PRESENCE_STALE`, `CAPACITY_REACHED`); report to the caller; a later attempt needs a new key. |
| F12 | Key reuse | 409 `IDEMPOTENCY_KEY_REUSED` | bug | the key scheme reused a key for another operation or conversation: fail loudly, fix the scheme. 409 `IDEMPOTENCY_RACE` is different: retry the same key. |
| F13 | Response lost or call timed out, outcome unknown | no response | transient | A1 to A3: same key. V1: repeat (409 `CONVERSATION_EXISTS` is success). V3, V4: reconcile first, then decide; there is no key. |
| F14 | Data the engine rejects | 400 `INVALID_ID`, `FIELD_REQUIRED`, `TEXT_TOO_LONG`, `TIMESTAMP_IN_FUTURE`, `INVALID_CAPACITY`, `FEATURE_DISABLED` (on S9) | bug | not retryable. Platform ids must follow the id rule (section 2.5). Omit `heartbeatAt` on presence so clock skew cannot trigger `TIMESTAMP_IN_FUTURE`. |
| F15 | Agent goes offline with conversations assigned | P1 succeeds; nothing else happens | by design | the engine does not release anything (section 2.3). If the product wants that, the platform calls A3 (reassign, multi-agent tenants only) or V4. |

### 7.3 `AllocationSeeder` sequence and the seed policy

`ensureTenant(tenantId, tenantName, queueId, agents)`, every call with `X-Tenant-Id: <tenantId>` and `X-Role: admin`, every step safe to repeat:

1. S1 `POST /tenants` with `{id: tenantId, name: tenantName}`. No 409 exists.
2. S3 `POST /queues` with `{id: queueId, name, description}` (upsert).
3. S4 `GET /policies/{policyId}`. On 404 `POLICY_NOT_FOUND`, S6 `POST /policies` with the body below; tolerate 409 `POLICY_EXISTS` (a concurrent seeder).
4. If the policy's `activeVersion` is null: S7 `POST /policies/{policyId}/publish`. A 422 `POLICY_INVALID` lists `issues[]` with JSON paths (for example `agentRanking.name`); fail the seed.
5. For each `SeedAgent`: S8 `GET /agents/{id}`. On 404, S9 `POST /agents` with `status` omitted (starts `OFFLINE`), `enabled` true, `maxActiveAssignments`, and `memberships: [{queueId: queueId}]`. If the agent exists and its membership or capacity must change, resend its current `status` and `enabled` (section 4.1).
6. Optional S10 `GET /features`: assert `allocation.ops.claim` and `allocation.ops.reassignment` are enabled; otherwise fail the seed with a clear message (F6c).

**Policy per queue.** **Recommendation:** one policy per queue, with an id derived from the queue id and matching the id rule, so adding an inbox is create and publish and never edits a published policy. The alternative, one policy per tenant listing every queue in `queueIds`, needs `PUT /policies/{id}` plus a new publish whenever an inbox is added (`PolicyController:65-70`). Overlapping scopes are legal and the most recently published one wins (`PolicyLifecycleService:125-128`; `PolicyRepository:102-110`).

**Seed policy body** (the engine-produced sample `docs/api/examples/policy-round-robin-fifo.json` with `tenantId` and `queueIds` replaced; `tenantId` and `version` are overwritten by the server anyway):

```json
{
  "policyId": "default-<queueId>",
  "version": 1,
  "tenantId": "<tenantId>",
  "name": "Default allocation",
  "description": "FIFO conversations, round-robin agents",
  "enabled": true,
  "queueIds": ["<queueId>"],
  "mode": "AUTO",
  "conversationSelection": {"name": "fifo", "params": {}},
  "eligibility": {"filters": [
    {"name": "tenant", "params": {}},
    {"name": "agent-enabled", "params": {}},
    {"name": "queue-membership", "params": {}},
    {"name": "availability", "params": {"heartbeatTtlSeconds": 60}},
    {"name": "capacity", "params": {}},
    {"name": "excluded-agents", "params": {}}
  ]},
  "agentRanking": {"name": "round-robin", "params": {}},
  "tieBreak": "AGENT_ID",
  "capacity": {"strategy": "count-based", "defaultMaxActive": 3, "countedStates": ["ACTIVE", "ACCEPTED", "ASSIGNED"]},
  "fallback": {"name": "keep-queued", "params": {}},
  "maxTraceCandidates": 200
}
```

What the validator requires (`core/policy/PolicyValidator.java`): `policyId`, `name` (`:47`), a non-empty `queueIds` (`:50`), `mode` (`:53`), `conversationSelection` (`:59`), `agentRanking` (`:68`), `tieBreak` (`:74`), `fallback` (`:82`), `capacity` with a known strategy and `countedStates` containing `ASSIGNED` (`:121, 133`), all six mandatory filters (`:114`) and `maxTraceCandidates` from 1 to 10000 (`:93`). **Send every field.** Two omissions fail quietly or late: a missing `maxTraceCandidates` deserialises to 0 and fails at publish with `INVALID_TRACE_LIMIT` (422); a missing `enabled` deserialises to `false` because the Jackson mapper tolerates omitted primitives (`svc/config/JacksonConfig:52-54`), and the published policy then answers `SKIPPED_POLICY_DISABLED` on every dispatch (inference; the `enabled = true` default exists only in the builder, `PolicyDefinition:57`). `heartbeatTtlSeconds` 60 matches the platform's stale-after value (overview D9).

Which ranking the platform wants (`round-robin` takes a per-queue cursor lock; `least-loaded` needs level `balanced` or higher, which `operations` includes) is a Plan 04 decision. Both are supported once the level is `operations`.

## 8. The one change to the allocation project (Step 6)

**Planned change (Plan 04, not made here):** add `allocation-engine/Dockerfile`, a multi-stage build on Temurin 25 that runs `./mvnw -pl service -am package -DskipTests`, and nothing else in the allocation project (overview D8: "unchanged except for an added `Dockerfile`").

**Why.** There is no image: `find allocation-engine -iname 'Dockerfile*'` found nothing and `infra/compose.yaml` containerises only PostgreSQL (inventory section 6.1). The platform stack is container-based and overview D8 puts the engine in its own container, so Compose needs an image to build.

Constraints the Dockerfile must respect (observed unless marked):

- The Maven enforcer requires JDK `[25,26)` and Maven `[3.9,)` (`backend/pom.xml:56-61`), and `maven.compiler.release` is 25 (`:27`). The builder stage must be a JDK 25 image and the runtime stage a Java 25 runtime. Image names such as `eclipse-temurin:25-jdk` and `:25-jre` are **not verified here**.
- The wrapper downloads Maven 3.9.16 from Maven Central (`backend/.mvn/wrapper/maven-wrapper.properties`), so the build needs network access, as do the dependencies.
- `-pl service -am` builds `core` and `service` (and the parent POM, inference from Maven's reactor rules); the Boot plugin repackages `service` into an executable jar (`backend/service/pom.xml:86-89`; the existing `backend/service/target/allocation-service-0.1.0-SNAPSHOT.jar` plus `.jar.original` show the repackaging).
- The jar path is `backend/service/target/allocation-service-0.1.0-SNAPSHOT.jar` (also used by `ui/e2e/start-service.mjs:11`).
- The build context should be `allocation-engine/` with `backend/` copied only. There is no `.dockerignore`, and `backend/core/target`, `backend/service/target` and `ui/node_modules` exist on this machine, so add one (inference).
- `-DskipTests` skips tests; the 102 service tests need Docker anyway (Testcontainers).
- Runtime settings: section 6.5. Health check: `GET /api/v1/health` or `/actuator/health`, both without headers.
- The workbench UI is not part of the jar (section 6.4); the image serves the API only.

**Limits from `HANDOVER.md` section 9 that matter here**

| Limit | Source | Matters for |
|---|---|---|
| Header auth only, labelled local development | `HANDOVER.md` section 9; `LocalDevAuthInterceptor:15-20` | the allocation port must stay on a private network; the platform asserts tenant and role itself |
| Throughput under saturation: about 0.3 % of saturated sweeps give up after three attempts, the conversation stays queued | `HANDOVER.md` section 9 | Plan 07 load numbers; harmless for correctness (next sweep) |
| First agent-row lock wait is unbounded; per-queue serialisation for round-robin | `HANDOVER.md` section 9; `concurrency-and-consistency.md` | client timeouts; ranking choice |
| `SnapshotBuilder` caps waiting conversations at 500 per decision | `HANDOVER.md` section 9; `SnapshotBuilder:22` | a queue deeper than 500 starves its tail until the head is served |
| The scheduler sweeps all tenants and queues on one thread | `concurrency-and-consistency.md`, "Known limits" | Plan 07 multi-tenant load |
| No retention for `allocation_decision` and `idempotency_record` | `concurrency-and-consistency.md`, "Known limits" | Plan 05 operations |
| Deferred strategies (rate limits, sticky routing, SLA-risk, weighted workload) | `HANDOVER.md` section 9 | nothing needed for the planned flow |
| `DispatchHook` is production code kept for tests | `HANDOVER.md` section 9; `svc/application/DispatchHook.java:8-12` | none; it is a no-op by default |

**Tests (documented, not re-run).** `HANDOVER.md` section 4 and the inventory: 216 core and 102 service tests (service: 1 opt-in load test skipped), counted from source with a word-boundary `@Test`/`@ParameterizedTest` match as 216 and 102. The service tests need Docker/Testcontainers (`postgres:17-alpine`). This task re-ran only the core suite (section 9). Tests that pin the behaviour used above: `DispatcherIT` (dispatch, claim, reassign, no-policy, not-allocatable), `AllocationApiIT` (HTTP flow, 409 `NO_ACTIVE_POLICY`, 422 `POLICY_INVALID`, roles, missing key), `HardeningIT` (limits, lifecycle guards, requeue rules, future heartbeats), `ConcurrencyIT` (races and invariants) under `IT/`.

## 9. Build check (Step 7)

| Item | Result |
|---|---|
| Command | from `allocation-engine/backend`: `JAVA_HOME="C:/Program Files/Java/jdk-25.0.3" ./mvnw -q -pl core verify` |
| Exit code | 0 |
| Console output | only two `Picked up JAVA_TOOL_OPTIONS: -Duser.timezone=Asia/Kolkata` lines (`-q`) |
| Tests | 216 run, 0 failures, 0 errors, 0 skipped: sum of the `tests`, `errors`, `skipped`, `failures` attributes over the 25 `backend/core/target/surefire-reports/TEST-*.xml` files, all written at 2026-10-27 08:17:19 to 08:17:20 +0530 during this run. The count equals the documented 216. |
| Side effects | `backend/core/target/` only. The core jar kept its earlier timestamp (2026-10-26 22:47), so the module was up to date and only the tests re-ran. No source file under `allocation-engine/` changed (`find -newermt` over the tree, excluding `target/`, found none). |
| Docker | not needed for `core`; the service module tests were not run, as instructed. |

## 10. Findings that differ from the brief or from the project documents

1. **"3 retries" is 3 attempts.** The brief and the engine documents ("retried up to 3 times") say retries; `MAX_ATTEMPTS = 3` and the loop `for (i = 0; i < MAX_ATTEMPTS; i++)` make three attempts in total (`Dispatcher:72, 235`), and the give-up log says "gave up after 3 attempts" (`:255`). Section 5.3 uses the code.
2. **No notification `afterCommit` hook exists.** The brief's upgrade path speaks of "the allocation `afterCommit` hook". The only `afterCommit` in the code increments feature metrics (`Dispatcher:408-423`). `docs/architecture/allocation-flow.md` (step 7, "then publish notifications"), ADR-003 ("commit, then notify") and ADR-009 ("published after commit through an in-process event") describe a notification that `concurrency-and-consistency.md` step 9 correctly calls "none in v1". The code agrees with the latter. The upgrade is therefore a new, additive engine change (section 7.1).
3. **`GET /queues` is not admin-only.** The brief writes "`GET/POST /api/v1/queues` (admin)". Only `POST` has `@RequiresRole("admin")` (`CatalogController:71-73`); every `GET` is open to `viewer`. The same holds for `GET /agents`, `GET /agents/{id}`, `GET /policies`.
4. **The 60 s heartbeat TTL is a policy parameter**, default 60, range 1 to 86400 (`AvailabilityFilter:32`), not a constant of the filter. The seed policy sets it explicitly.
5. **`docs/api/openapi.yaml` is unsuitable for client generation.** 58 operations declare a required query parameter `caller` of type `Caller` (the `Caller` argument is resolved from the interceptor, not from the query string), none documents a 4xx response, bodies are `*/*`, and the server URL is `http://localhost` (`grep -c "name: caller" openapi.yaml` gives 58; no `"4xx"` response key). Use section 4 instead.
6. **Stale engine documents.** `docs/operations/local-development.md:48` says 179 core and 49 service tests, and `:58` lists only `V1__schema.sql`; the code has five migrations and `HANDOVER.md`/the source count give 216 and 102. `docs/architecture/feature-levels-and-packaging.md` describes a module layout (`jdbc`, `starter`, `web`, `openfeature`, `bom`) "after plan B"; `backend/pom.xml:20-23` lists only `core` and `service`, so plan B is not implemented. ADR-004 says policy JSON carries a `schemaVersion`; no such field exists (`grep schemaVersion backend` finds nothing).
7. **`target/` is not covered by an ignore rule.** The brief calls it "the ignored target/ directory". There is no `.gitignore` under `allocation-engine/` and none at `D:\BYTEFLOCK` (inventory section 9); the whole engine directory is untracked in a repository with no commits. No effect on this task: the build wrote only into the pre-existing `backend/core/target/`.
8. **Unknown JSON properties in requests** are neither rejected nor accepted by any test or code path found here. Not verified.

No other statement in the brief contradicted the code: the roles, status codes and error codes in the brief's Step 2 list (including `CONVERSATION_EXISTS`, `QUEUE_NOT_FOUND`, `CONVERSATION_CLOSED`, `CONVERSATION_NOT_FOUND`, `INVALID_REQUEUE`, `NOT_ASSIGNED`, the `excludePreviousAgent` default, the flags, `saveQueue` upsert, `ensureTenant` idempotence and the `Limits.id` rule) match the code, and so do the Step 3 observations about non-`QUEUED` dispatch.

## 11. Verification against master spec 2.2 and the typed boundary (Step 8)

**Master spec 2.2 bullets** (as listed in [workspace-inventory.md](workspace-inventory.md) section 10) and where this document answers each:

| Spec 2.2 bullet | Where answered here |
|---|---|
| Domain objects and status model | section 2.1 to 2.4; tables and core records also in inventory 6.2 |
| Public API/interface and request/response DTOs | section 4 (calls, headers, bodies, errors), 4.7 (shapes), 3 (conventions) |
| Selection policies and eligibility rules | section 2.6, 2.3 (availability), 7.3 (seed policy) |
| Agent availability/presence model and how it is obtained | section 2.3, P1/P2 in 4.2, F15 in 7.2 |
| Capacity reservation and concurrency guarantees | section 2.2, 5.1, 5.3 |
| Database schema, transaction boundaries and idempotency | section 2.2, 5.1, 5.2 (schema lines cited; full table list in inventory 6.2) |
| How assignments are recorded, changed, released and retried | section 2.1 (transitions), 4.3, 4.4, 5.4, 6.1, 7.2 |
| How it starts locally; ports, databases, credentials, dependencies | inventory 6.1 (unchanged); section 6.3 to 6.5 and 8 for the platform's container |
| Tests, limitations, known defects and TODOs | section 8 (tests and limits), 9 (build check), 10 (findings); `grep TODO\|FIXME` result is in inventory 6.2 |

**Brief Step 1 to 8 coverage:** domain, statuses, index, capacity, TTL and outcomes: 2; every endpoint of the brief's list with method, path, headers, body, success and error codes: 4.1 to 4.5; `Limits.id` and the UUID check: 2.5; dispatch transaction, idempotency, `IDEMPOTENCY_KEY_REUSED`, `CONFLICT_RETRYABLE`, three attempts, the non-`QUEUED` branch: 5; scheduler, feature levels, demo seeding, CORS, bind address, UI location: 6; integration decision and failure matrix (allocation down, tenant/queue missing, `CONVERSATION_EXISTS`, `NOT_ASSIGNED`, `INVALID_REQUEUE`, `CONFLICT_RETRYABLE`, `QUEUED_*`, `REJECTED_INVALID_INPUT`): 7; the Dockerfile change and `HANDOVER.md` section 9: 8; build check: 9.

**The typed boundary** is `assignment.AllocationClient` with `AllocationSeeder`, plus `AllocationResult`, `AllocationConversationState`, `AgentPresenceView`, `Availability`, `AllocationUnavailableException`, `AllocationRejectedException` and `SeedAgent` (overview section 5, glossary entry `assignment` [04]). All ten `AllocationClient` methods (`findConversation`, `createConversation`, `dispatch`, `claim`, `reassign`, `resolve`, `requeueReopen`, `presence`, `agents`, `healthy`) and `AllocationSeeder.ensureTenant` are mapped to engine calls in section 4.8. The `Availability` enum matches the engine's `AvailabilityStatus` value for value; `AgentPresenceView` fields exist on `AgentRecord`; `AllocationResult` fields exist on `AllocationDecision`. One gap against D8: the projection field `assignment_decision_id` cannot be filled from V2 for assignments made by the sweep; it needs O1 (section 4.6).

## 12. Not found, and open points for Plan 04

**Not found in the engine** (each is a finding):

- a Java client library, an outbound event or webhook mechanism, and an `AllocationEventListener` (section 7.1);
- a computed "effective availability" flag, and any automatic release of an offline agent's conversations (section 2.3);
- an endpoint that removes an id from `excludedAgentIds` (section 4.3);
- update or delete for tenants and agents, and a filter or paging on `GET /agents` (4.2);
- paging on `GET /conversations` (clamped to 1000) and `GET /allocations/decisions` (clamped to 500) (4.6);
- a retention job for `allocation_decision` and `idempotency_record` (5.2);
- a Prometheus endpoint (4.5);
- authentication beyond the `X-Tenant-Id` and `X-Role` headers (3).

**Open points** to settle in Plan 04, each with the code fact it depends on:

1. The key scheme and the generation counter (section 5.5).
2. One policy per queue or per tenant, and `round-robin` versus `least-loaded` (section 7.3).
3. Whether `AssignmentWorker` still dispatches on `REQUESTED` or lets the sweep do it and only polls (section 6.1); and whether to call O3 to shorten the wait.
4. Whether to read O1 for `assignment_decision_id` on sweep assignments (section 4.6).
5. Whether an agent going offline should trigger reassign or requeue (F15), knowing reassign always excludes the previous agent.
6. The poll interval for `QUEUED` conversations (at least the sweep delay) and how a conversation stuck on `QUEUED_NO_ELIGIBLE_AGENT` surfaces to operators (F8).
7. HTTP client timeouts and the back-off for F1 and F7.
8. Retention of the engine's audit tables (Plan 05).
