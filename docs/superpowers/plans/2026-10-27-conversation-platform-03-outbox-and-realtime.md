# Conversation Platform — Plan 03: Transactional Outbox and Realtime (Phase 7, Phase 8 transport, typing)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Read `2026-10-27-conversation-platform-00-overview.md` first (D6, D12, D14, D17, §5 glossary) and Plan 02 (the outbox table and appender already exist).

**Goal:** Publish committed outbox events to Centrifugo reliably (claim/lease/retry/park/replay, at-least-once, metrics), issue scoped Centrifugo tokens for the new channels, add ephemeral typing, pin the event contracts (JSON Schema + AsyncAPI), and prove the fault cases of master spec 9.4 / 14.3 against real PostgreSQL and a Centrifugo stub.

**Architecture:** `outbox` module: `OutboxPublisher` (scheduled worker, `runOnce()` for tests) claims a batch with `FOR UPDATE SKIP LOCKED` + lease, commits the claim, then calls `RealtimePublisher.broadcast` outside any transaction, then marks rows. `realtime` module gains `broadcast`. `conversation` module gains `ChannelAuthorizer`, realtime token endpoints and `TypingService`. Centrifugo `chat` namespace gets short history + forced recovery as an optimisation only.

**Tech Stack:** as Plan 02 plus Micrometer (already present), Centrifugo v6.10.2 server API (`POST /api/broadcast`, `X-API-Key`, response `{result:{responses:[…]}}` or `{error:{code,message,temporary}}`).

**Spec:** master spec 7.2, 9, 10.3–10.4, 11.3, 14.3, 15; ADR-0007; `docs/architecture/realtime-and-recovery.md`.

## Global Constraints

- Overview §4 applies. Additionally:
- The publisher never runs inside the claim transaction; the claim commits first, then the network call happens, then a separate statement marks the row.
- Every publish carries `idempotency_key = eventId` (Centrifugo dedupes within 5 minutes on the memory engine); the platform still documents at-least-once.
- Attempts are counted at claim time (`claimBatch` sets `attempts = attempts + 1`), so a worker that crashes after claiming still uses up an attempt. Backoff after a failure: `next_attempt_at = now + min((base × 2^(attempts − 1)) ± 20% jitter, max)` (jitter before the cap, so a delay never exceeds `max`; the first retry waits about `base`); a failed row whose claimed `attempts ≥ max-attempts` → `PARKED`, never dropped; `last_error` is a bounded class name/HTTP code, never the payload.
- Typing events are published directly (no outbox, no DB row) and are authorized and rate-limited exactly like messages.
- A browser never receives `CENTRIFUGO_HTTP_API_KEY` or the HMAC secrets; subscription tokens are bound to `sub` and the exact channel with the existing 5-minute TTL.
- Tests that need time control use the `TestClock` introduced in T2; no `Thread.sleep` for backoff logic.

## Review Focus

1. Centrifugo returns HTTP 200 with an `error` object (its default error mode) → treated as a failure and retried, not marked published (`OutboxPublisherIT.centrifugoErrorObjectIsAFailure`).
2. A worker's lease expires while it is still mid-publish; a second worker republishes; the first then tries to mark → the mark is a no-op for the lost lease and the row stays consistent (`OutboxPublisherIT.lostLeaseMarkIsIgnored`).
3. A subscription-token request for `chat:inbox:<tenantA>:<inboxOfTenantB>` (mismatched pair) → 403 (`RealtimeTokenIT.inboxChannelTenantMustMatchInboxOwner`).
4. Typing is requested with `typing:true` 50 times in a loop by one client → one publish per 2 s, the rest 429, no DB writes (`TypingIT.typingIsRateLimitedAndNeverPersisted`).
5. The outbox backlog has 10,000 pending rows → one `runOnce` processes `batch-size` rows, the lag gauge reports the oldest age, no statement loads the full table (`OutboxPublisherIT.largeBacklogIsProcessedInBoundedBatches`).

---

### Task 1: `RealtimePublisher.broadcast` and stub support

**Files:**
- Modify: `realtime/RealtimePublisher.java` (add `public boolean broadcast(List<String> channels, Object data, String idempotencyKey)` posting `{channels, data, idempotency_key}` to `/api/broadcast`; success needs HTTP 200, no top-level `error` and no `error` in any `result.responses[i]` (broadcast reports per-channel failures there); shared `send(path, body)` helper; metrics `chat.realtime.publications{outcome,kind=publish|broadcast}`), `realtime/package-info.java`.
- Modify: `src/test/java/com/byteflock/chatplatform/CentrifugoStub.java` (record path and parsed JSON body; `respondForPath(String path, int status, String body)`; `List<Request> requestsFor(String path)`; `failNext(int n)` to simulate transient errors; `setDown(boolean)` to simulate downtime (while down, the handler closes each connection without writing a response so the client sees a connection error; the port is never rebound, which is unreliable on Windows); `delayNext(Duration)` for timeouts; `reset()` (clear requests and programmed responses), called from `PlatformIntegrationTest`'s `@BeforeEach`).
- Modify: `realtime/RealtimePublisherTests.java`.

**Interfaces:**
- Consumes: existing `RealtimePublisher.publish`.
- Produces: `broadcast(...)` as in overview §5; stub API used by T2–T4 and Plan 04.

- [ ] **Step 1: Write tests** in `RealtimePublisherTests`: `broadcastPostsChannelsDataAndIdempotencyKey` (stub body has `channels` array, `data`, `idempotency_key`), `broadcastReturnsFalseOnErrorObject` (200 + `{"error":{"code":103,"message":"permission denied"}}` → false), `broadcastReturnsFalseOnHttp500`, `broadcastReturnsFalseWhenUnreachable` (closed port; completes within 3 s), `broadcastReturnsFalseWhenAnyChannelFails` (200 + `{"result":{"responses":[{"result":{}},{"error":{"code":102,"message":"unknown channel"}}]}}` → false), `broadcastReturnsFalseOnTimeout` (stub delays 3 s → false shortly after the 2 s client timeout; master spec 14.3), `publishStillWorks`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=RealtimePublisherTests` → FAIL → implement → PASS.
- [ ] **Step 3: Commit** — `git add services && git commit -m "feat(realtime): Centrifugo broadcast with idempotency key"`.

### Task 2: Outbox publisher worker

**Files:**
- Create: `outbox/internal/OutboxProperties.java` (`app.outbox.*`: `enabled`, `pollDelay`, `batchSize`, `lease`, `maxAttempts`, `baseBackoff`, `maxBackoff`), `outbox/internal/OutboxPublisher.java` (`@Scheduled(fixedDelayString="${app.outbox.poll-delay}")`; the bean always exists and the scheduled tick returns immediately when `app.outbox.enabled=false` — no `@ConditionalOnProperty`, tests call `runOnce()`; `public int runOnce()` returns rows processed; worker id = hostname + random suffix), `outbox/Backoff.java` (public in the module root — Plan 04's assignment worker reuses it; pure: `static Instant next(Instant now, int attempts, Duration base, Duration max, RandomGenerator rng)`), `outbox/internal/OutboxHook.java` (test seam: `afterPublishBeforeMark(Runnable)` one-shot), `outbox/internal/OutboxMetrics.java` (gauges `outbox.pending.count`, `outbox.parked.count`, `outbox.pending.oldest_age_seconds`; counter `outbox.publish.attempts{outcome=success|failure|parked}`; timer `outbox.publish.duration`).
- Modify: `outbox/internal/OutboxRepository.java` (add `List<OutboxRow> claimBatch(String workerId, int limit, Instant now, Duration lease)` as one `UPDATE outbox_events SET locked_by=:worker, locked_until=:until, attempts = attempts + 1 WHERE event_id IN (SELECT event_id FROM outbox_events WHERE status='PENDING' AND next_attempt_at <= :now AND (locked_until IS NULL OR locked_until < :now) ORDER BY next_attempt_at, created_at LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING …`; `int markPublished(UUID eventId, String workerId, Instant now)` (`WHERE locked_by = :worker`); `int markFailed(UUID eventId, String workerId, Instant nextAttemptAt, String error, boolean parked)` (also `WHERE locked_by = :worker`; clears the lock); `int replay(UUID eventId)` (PARKED → PENDING, attempts 0, next now); `long countByStatus(OutboxStatus)`; `Optional<Instant> oldestPendingCreatedAt()`; `List<OutboxEventView> list(OutboxStatus, int limit)`).
- Modify: `src/test/java/com/byteflock/chatplatform/PlatformIntegrationTest.java` (import a `@TestConfiguration` providing `@Primary TestClock` — a mutable `Clock` with `advance(Duration)` and `set(Instant)`, reset to the real time in the base `@BeforeEach`; `app.outbox.enabled=false` stays so tests drive `runOnce()`).
- Modify: `application.yml` (`app.outbox.*` defaults per overview §5; `app.outbox.enabled: ${APP_OUTBOX_ENABLED:true}`), `ModulithplatformserviceApplication.java` (`@EnableScheduling` if not already present).
- Create tests: `outbox/BackoffTest.java`, `outbox/OutboxPublisherIT.java`.

**Interfaces:**
- Consumes: T1 `broadcast`; Plan 02 appender/table; `TestClock`.
- Produces: the worker that Plans 04–07 rely on; `OutboxRepository.list/replay` for T5 admin.

- [ ] **Step 1: Write `BackoffTest`**: `growsExponentiallyAndCaps` (attempts 0..10 with base 1 s, max 60 s → 1,2,4,8,16,32,60,60,… before jitter), `jitterStaysWithin20Percent` (fixed-seed RNG; 1000 samples), `neverExceedsMax` (attempts 20, max 60 s, 1000 samples → every delay ≤ 60 s).
- [ ] **Step 2: Run** → FAIL → implement `Backoff` → PASS.
- [ ] **Step 3: Write `OutboxPublisherIT`** (extends `PlatformIntegrationTest`; events inserted via `OutboxAppender` inside `TransactionTemplate`): `publishesPendingEventsWithBroadcastAndMarksPublished` (stub `/api/broadcast` gets `channels` = row channels and `idempotency_key` = eventId; row `PUBLISHED`, `published_at` set, `locked_by` null), `centrifugoDownKeepsEventPendingWithBackoff` (stub down → after `runOnce`: `PENDING`, `attempts=1`, `next_attempt_at > now`, `last_error` = exception class name, no payload text in `last_error`), `centrifugoErrorObjectIsAFailure`, `centrifugoRestartDrainsBacklog` (stub down → run → stub up again → advance clock past backoff → run → `PUBLISHED`), `workerCrashAfterClaimIsRecoveredAfterLease` (call `claimBatch` directly as worker `A`, do not publish; `runOnce` as `B` before lease expiry publishes nothing; advance clock beyond lease; `runOnce` publishes), `workerCrashAfterPublishBeforeMarkRepublishes` (hook throws after stub records publish → row still `PENDING` with `attempts=1`; advance clock; `runOnce` → stub has 2 broadcasts with the same `idempotency_key`; row `PUBLISHED`), `lostLeaseMarkIsIgnored` (`markPublished` with a stale worker id returns 0; row unchanged), `twoWorkersConcurrentlyClaimDisjointBatches` (200 events; two publishers with distinct worker ids run `runOnce` concurrently in loops until pending = 0; stub received exactly 200 distinct event ids and 200 requests; all rows `PUBLISHED`), `poisonEventIsParkedAfterMaxAttempts` (stub always 500; loop: `runOnce`, advance clock past `next_attempt_at`; after `max-attempts` → `PARKED`, `outbox.parked.count=1`, further runs do not touch it), `replayParkedEventResetsToPending`, `largeBacklogIsProcessedInBoundedBatches` (insert 10,000 rows with one multi-row SQL in the test, `created_at` one minute in the past; `runOnce` processes exactly `batch-size`; `outbox.pending.oldest_age_seconds` > 0), `metricsExposePendingAndParkedCounts`.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=OutboxPublisherIT` → FAIL → implement repository claim SQL, `OutboxPublisher.runOnce` (claim in `TransactionTemplate`; for each row: `broadcast` → `markPublished` or `markFailed`; every log line carries `eventId`, `eventType`, `attempts` and the row's `trace_id` so a message can be followed from the API request to the publish), hook, metrics, properties, `TestClock` → PASS.
- [ ] **Step 5: Run** `./mvnw -q test -Dtest='ModularityTests,MessageWriteIT,OutboxPublisherIT'` → PASS (`outbox → realtime` is the only new dependency).
- [ ] **Step 6: Commit** — `git add services && git commit -m "feat(outbox): leased batch publisher with backoff, parking, metrics and fault tests"`.

### Task 3: Centrifugo configuration, channel authorization and token endpoints

**Files:**
- Modify: `infra/centrifugo/config.json` (namespace `chat`: add `"history_size": 100, "history_ttl": "300s", "force_recovery": true`; keep client subscribe/publish denied; the key names were verified against https://centrifugal.dev/docs/server/channels on 27 Oct 2026 — channel options apply per entry of `channel.namespaces` — and Step 1 re-checks them against the pinned image), `infra/README.md`, `docs/setup/tool-versions.md` (record the `checkconfig` result).
- Create: `conversation/internal/ChannelAuthorizer.java` (`void authorize(Caller caller, String channel)` → throws `ConversationConflictException`-style 403 `FORBIDDEN` or 400 `VALIDATION_FAILED` for malformed channels; rules per D14: CONTACT → own conversation channel only; AGENT → inbox channel of its tenant (inbox must exist and belong to the tenant), conversation channel of its tenant), `conversation/internal/ConversationRealtimeTokenController.java` (not `RealtimeTokenController`: its default bean name would clash with the demo's `chat.RealtimeTokenController` and fail startup until Plan 06 deletes the demo; `GET /api/v1/widget/realtime/connection-token`, `GET /api/v1/widget/realtime/subscription-token?channel=`, `GET /api/v1/agent/realtime/connection-token`, `GET /api/v1/agent/realtime/subscription-token?channel=`; Centrifugo `sub` = `<tenantId>:<contact|agent>:<principalId>`; counter `realtime.tokens{type=connection|subscription,outcome=issued|denied}` so subscription denials are visible to the application as master spec 15 requires).
- Create tests: `conversation/RealtimeTokenIT.java`, `conversation/ChannelsTest.java` (unit: `Channels.conversation/inbox/conversationId/inboxRef` round-trip and rejection of `chat:inbox:a:b:c`, uppercase UUIDs, injection attempts).

**Interfaces:**
- Consumes: `RealtimeTokenIssuer` (unchanged), `Channels` (Plan 02), `TenantDirectory`.
- Produces: the four token endpoints of overview §5; the authorization rules the E2E "non-participant cannot subscribe" test relies on.

- [ ] **Step 1: Verify Centrifugo keys** with Docker running: `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/infra/centrifugo/config.json:/centrifugo/config.json:ro" centrifugo/centrifugo:v6.10.2 centrifugo checkconfig --config=/centrifugo/config.json` (without `MSYS_NO_PATHCONV=1` Git Bash rewrites the `/centrifugo/…` arguments) → exit 0 after the change (if the keys are rejected, consult `centrifugo defaultconfig -d` from the same image and fix the names; record the outcome in `tool-versions.md`).
- [ ] **Step 2: Write `ChannelsTest`** and `RealtimeTokenIT`: `contactGetsConnectionTokenWithScopedSubject`, `contactOwnConversationChannelOk` (decoded JWT `channel` claim equals the request, `exp − iat ≤ 300 s`), `contactOtherConversationIs403`, `contactInboxChannelIs403`, `agentOwnInboxChannelOk`, `agentOtherTenantInboxIs403`, `inboxChannelTenantMustMatchInboxOwner`, `agentConversationOfOtherTenantIs403`, `malformedChannelIs400`, `noBearerIs401`.
- [ ] **Step 3: Run** → FAIL → implement → PASS.
- [ ] **Step 4: Live check** (stack running: `docker compose up -d --wait`, backend in Mode A): with a contact token, obtain a subscription token for another contact's conversation → 403; subscribe with the official SDK in a small Node snippet to a channel whose token was issued for a different channel → Centrifugo rejects with code 103 (record the observed output in the ledger).
- [ ] **Step 5: Commit** — `git add infra services docs && git commit -m "feat(realtime): tenant-scoped channel authorization, token endpoints and Centrifugo recovery config"`.

### Task 4: Ephemeral typing

**Files:**
- Create: `conversation/internal/TypingService.java` (`void typing(Caller caller, UUID conversationId, boolean typing)`: participant check via `ConversationRepository`, `RateLimiter.tryAcquireInterval("typing:" + tenant + ":" + principal + ":" + conversation, app.rate-limits.typing-interval)` → 429 `RATE_LIMITED`, then `RealtimePublisher.publish(Channels.conversation(id), new EventEnvelope(UUID.randomUUID(), "typing", 1, tenant, id, now, Map.of("participantType", caller.type(), "participantId", caller.principalId(), "typing", typing)))`; a failed publish is logged at debug and still returns normally), `conversation/internal/TypingController.java` (`POST /api/v1/widget/conversations/{id}/typing`, `POST /api/v1/agent/conversations/{id}/typing`, body `{typing:boolean}` → 202).
- Create tests: `conversation/TypingIT.java`.

**Interfaces:**
- Consumes: `RateLimiter` (tenancy root), `EventEnvelope`, `RealtimePublisher.publish`.
- Produces: the `typing` event (`typing.v1` schema in T5) consumed by Plan 06 clients.

- [ ] **Step 1: Write `TypingIT`**: `participantTypingIsPublishedToConversationChannelOnly` (stub `/api/publish` request channel = conversation channel; payload envelope `eventType="typing"`, `data.typing=true`), `typingIsRateLimitedAndNeverPersisted` (50 calls → 1 publish + 49 × 429 within the interval; `messages`/`outbox_events` counts unchanged), `stopTypingAfterIntervalIsAccepted` (advance `TestClock` 2 s → `typing:false` → 202, second publish), `nonParticipantIs403`, `crossTenantIs403`, `centrifugoDownStillReturns202`.
- [ ] **Step 2: Run** → FAIL → implement → PASS.
- [ ] **Step 3: Commit** — `git add services && git commit -m "feat(conversation): ephemeral rate-limited typing indicators"`.

### Task 5: Event contracts, outbox admin endpoints, documentation

**Files:**
- Create: `libs/contracts/json-schema/chat/event-envelope.v1.schema.json` (required `eventId`, `eventType`, `schemaVersion`, `tenantId`, `conversationId` (nullable), `occurredAt`, `data`), `message-created.v1.schema.json` (envelope with `eventType: const "message.created"`, `data.message` → `message.v1`), `conversation-created.v1.schema.json`, `conversation-updated.v1.schema.json` (`data.conversation` → `conversation.v1`, `data.change` enum per D12), `typing.v1.schema.json`; rewrite `libs/contracts/asyncapi/chat-realtime.yaml` (channels `chat:conversation:{conversationId}`, `chat:inbox:{tenantId}:{inboxId}`; messages per event; at-least-once statement; subscriber rules), `libs/contracts/json-schema/README.md`.
- Create: `outbox/internal/OutboxAdminController.java` (`GET /api/v1/admin/outbox?status=PENDING|PUBLISHED|PARKED&limit=` → `OutboxEventView[]`; `POST /api/v1/admin/outbox/{eventId}/replay` → 200 view or 404; `@ConditionalOnProperty(app.local-dev.enabled)`, `@RequiresPrincipal(ADMIN)`).
- Create tests: `outbox/OutboxAdminIT.java`, `outbox/EventEnvelopeContractTest.java` (serializes one envelope per event type with the Boot `JsonMapper` and asserts every `required` key of the matching schema file (read with Jackson from `../../libs/contracts/json-schema/chat/`) is present and that `eventType` matches the schema `const`).
- Modify: `libs/contracts/openapi/platform-api.yaml` (admin outbox paths, `AdminToken` apiKey security scheme), `docs/architecture/realtime-and-recovery.md` (final values and the `runOnce` lifecycle), `docs/runbooks/local-stack.md` (inspect/replay parked events; `curl` examples with `X-Admin-Token`), ledger.

- [ ] **Step 1: Write `EventEnvelopeContractTest` and `OutboxAdminIT`** (`listRequiresAdminToken` (401), `listsParkedEvents`, `replayResetsParkedEvent`, `replayUnknownIs404`, `adminRoutesAbsentWhenLocalDevDisabled`).
- [ ] **Step 2: Run** → FAIL → write schemas/AsyncAPI/controller → PASS; `pnpm contracts:validate` → PASS.
- [ ] **Step 3: Run** `./mvnw -q verify` → all green; record counts in the ledger.
- [ ] **Step 4: Commit** — `git add libs services docs && git commit -m "feat(contracts): realtime event schemas, AsyncAPI and outbox admin endpoints"`.
