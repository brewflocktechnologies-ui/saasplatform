# ADR-0002: One Spring Boot deployment with internal module boundaries

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Domain boundaries (tenancy, websites, conversations, messaging, billing) are not yet proven. Distributed services
would add network, consistency and operational cost before the domain is understood.

## Decision

Build `modulithplatformservice` as a modular monolith: Spring Boot 4.1.2, Java 25, one deployable. Each top-level
package under `com.byteflock.chatplatform` is an application module verified by Spring Modulith 2.1.2
(`ApplicationModules.verify()` in `ModularityTests`). `shared` holds only minimal cross-cutting configuration.

## Alternatives

- Microservices from day one: premature; needs a broker, distributed tracing discipline and per-service DBs now.
- Plain layered monolith without enforced modules: fast, but boundaries erode silently.

## Consequences

- Modules can later be extracted along verified boundaries if scaling or team topology requires it.
- Spring Modulith compatibility must be checked on every Boot upgrade (currently Boot 4.0–4.2 supported).
- Validated: modularity test passes; one deployable image builds and runs.
