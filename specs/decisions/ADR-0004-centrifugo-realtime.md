# ADR-0004: Centrifugo as the real-time server

- **Status:** Accepted (used by the chat MVP since 2026-10-25)
- **Date:** 2026-10-25

## Context

Live chat needs WebSocket/SSE fan-out to many browser connections. Holding those connections in the Spring Boot
monolith would tie connection scaling to business-logic scaling.

## Decision

Run Centrifugo (v6.10.2) as an independent container. The backend will publish via Centrifugo's server API and
issue short-lived connection/subscription tokens; browsers connect to Centrifugo directly. No message broker (NATS
etc.) is introduced in this setup; Centrifugo's in-memory engine is enough locally.

## Alternatives

- Spring WebSocket/STOMP in the monolith: fewer moving parts, but couples connection scale to the app.
- Managed services (Pusher, Ably): vendor lock-in, conflicts with vendor-agnostic deployment goal.

## Consequences

- Needs a Redis/NATS-backed engine for multi-node Centrifugo in UAT/production (decide in a later spec).
- Chat MVP (specs/mvpsetup.spec.md) uses: namespace `chat` with client publish/subscribe denied, connection JWTs and
  separate-secret subscription JWTs bound to actor + exact channel, backend publication via the HTTP API after commit.
- **Still not production-safe:** demo identities are hardcoded; real authentication, tenant-scoped channels, an outbox
  and a multi-node engine are required before UAT.
- Validated: container healthy via `/health`; invalid connection token → disconnect 3500; token-less subscription
  → 103; client publish → 103; live delivery both ways in the two-browser E2E test.
