# ADR-0007: Transactional outbox relayed to Centrifugo

- **Status:** Accepted (design); fault validation pending in Plan 03 (`OutboxPublisherIT`), lag and delivery
  validation pending in Plan 07 (load profiles)
- **Date:** 2026-10-27
- **Master spec name:** ADR-003 "transactional outbox and Centrifugo"
- **Builds on:** [ADR-0004](ADR-0004-centrifugo-realtime.md) (Centrifugo as the realtime server)
- **Details:** [realtime-and-recovery.md](../../docs/architecture/realtime-and-recovery.md)

## Context

**Existing:** the demo publishes to Centrifugo after commit with one HTTP call and no retry, so a crash or a Centrifugo
outage loses the event (at most once; `realtime/RealtimePublisher.java`, workspace-inventory.md section 4.2). ADR-0004
already listed "an outbox" as required before UAT. Master spec 9 requires a durable outbox with claim, lease, retry,
parking, metrics and at-least-once delivery, and fault tests (9.4). Overview §4 forbids new infrastructure (no Redis,
Kafka, NATS, workflow engine or extra service). None of the reference systems keeps a durable record of emitted events
([reference-comparison.md](../../docs/research/reference-comparison.md) row 8).

## Options

1. **Outbox table in PostgreSQL + a polling publisher in the backend**: rows claimed with `FOR UPDATE SKIP LOCKED` and
   a lease, published with Centrifugo's server API `broadcast`, marked, retried with backoff, parked.
2. **Publish after commit with in-memory retries.** Simple, but a crash between commit and publish still loses the
   event.
3. **Change data capture** (logical replication or Debezium) into a broker, then to Centrifugo. Removes polling, but
   adds a connector and a broker, and needs the same parking and replay semantics elsewhere.
4. **A message broker** written in the transaction's place (Kafka, NATS) consumed by Centrifugo or a relay. Needs a
   distributed transaction or an outbox anyway; new infrastructure.
5. **Let Centrifugo consume the outbox table itself.** Centrifugo documents asynchronous consumers; this plan did not
   verify that feature against the pinned v6.10.2, and it would move retry, parking and replay into Centrifugo
   configuration that the platform cannot test with its stub.

## Decision

Option 1 (overview D6):

- One logical `outbox_events` row per durable domain event (`message.created`, `conversation.created`,
  `conversation.updated`), written by `OutboxAppender` inside the business transaction (it throws outside one), with
  the event's `channels text[]` and the complete envelope as `payload`.
- `OutboxPublisher` claims a batch (`batch-size` 100) in a short transaction, commits, publishes each row outside any
  transaction with `POST /api/broadcast` and `idempotency_key = eventId`, then marks it `PUBLISHED` or schedules a
  retry: `min(1 s × 2^(attempts−1) ± 20 %, 60 s)`, attempts counted at claim, `PARKED` after 10 attempts, lease 30 s,
  poll 500 ms. Marks are guarded by `locked_by`. Parked rows are listed and replayed through the local-dev admin API.
- **Delivery is at least once.** Clients deduplicate by `eventId`, message `id` and `sequence`, and reconcile from the
  API on every subscribe; no part of the platform depends on a stronger guarantee.
- Ephemeral `typing` events bypass the outbox and are published directly; losing one changes no state.
- Centrifugo history (`history_size: 100`, `history_ttl: "300s"`, `force_recovery: true`) is a short-gap optimisation,
  never the source of truth.

## Consequences

- An event is published if and only if its transaction committed (zulip.md SI-7), and it survives Centrifugo outages
  and backend crashes.
- Write amplification: every message writes a second row holding the body in `payload`. Published rows are kept (they
  serve inspection and replay) and are deleted only with their conversation by the purge; a cleanup of old
  `PUBLISHED` rows is NEXT.
- Relay latency adds about the poll delay (500 ms) to every realtime delivery.
- Clients must tolerate duplicates and out-of-order arrival (retries, two workers, two channels).
- A Centrifugo outage longer than about 4 minutes parks events; the API reconcile still brings every client up to date,
  but parked rows wait for an operator.

## Operational impact

- Metrics: `outbox.pending.count`, `outbox.parked.count`, `outbox.pending.oldest_age_seconds`,
  `outbox.publish.attempts{outcome}`, `outbox.publish.duration`. Alert on parked > 0 and on a growing oldest age.
- Runbook: inspect with `GET /api/v1/admin/outbox?status=PARKED`, replay with `POST /api/v1/admin/outbox/{eventId}/replay`
  (`docs/runbooks/local-stack.md`, Plan 03 T5). Replays are logged with `eventId`.
- Every publisher log line carries `eventId`, `eventType`, `attempts` and the originating `trace_id`; never the
  payload.
- One cheap claim query per instance every 500 ms (partial index `ix_outbox_due`).

## Evidence that would trigger a change

- Plan 07: `outbox.pending.oldest_age_seconds` keeps growing during `baseline` or `burst`, or delivery p95 exceeds
  1000 ms in `smoke`/`baseline` with the relay as the measured cause; first tune `batch-size` and `poll-delay`, then
  consider option 3.
- More than one consumer of domain events appears (webhooks to tenants, analytics, search indexing): a broker or CDC
  becomes worth its cost.
- Multi-node Centrifugo with a Redis or NATS engine (ADR-0004 consequence): re-check `broadcast` idempotency and history
  semantics on that engine.
- Option 5 is verified to support claim, retry, parking and replay with observable failures on the pinned version.
- `outbox_events` growth affects claim or purge query plans (Plan 07 T4 `EXPLAIN` output).
