# Architecture

## Overview

```text
 Browser (host)
  ├── http://localhost:3000  chatdashboardapp  (Next.js 16, React 19)   ─┐ fetch /api/v1/* (CORS allow-list)
  └── http://localhost:5173  chatwidget        (Lit 3 web component)     │
                                                                          ▼
                         modulithplatformservice (Spring Boot 4.1, Java 25, :8080)
                          ├── bootstrap/  GET /api/v1/bootstrap  ── JDBC (platform_app, DML only) ──► PostgreSQL 18
                          ├── health/     GET /api/v1/status     ── HTTP /health ──────────────────► Centrifugo 6
                          └── shared/     CORS, problem details, trace-id header, OTel log bridge
                                │ OTLP/HTTP traces + logs                ▲ /actuator/prometheus (scrape)
                                ▼                                        │
                         OTel Collector ──► Tempo (traces)          Prometheus ◄── also scrapes Collector :8888
                                        └─► Loki  (logs, /otlp)          │
                                                     ▲                   │
                                                     └──── Grafana ──────┘  (provisioned datasources + dashboard)

 flyway-migration (one-shot job, platform_migrator = DDL owner) ──► PostgreSQL schema "platform"
```

## Projects and boundaries

| Nx project | Folder | Tags | May depend on |
|---|---|---|---|
| `chatdashboardapp` | `apps/chatdashboardapp` | `type:app`, `platform:react` | ui/tokens/contracts libs that are React or shared |
| `chatwidget` | `apps/chatwidget` | `type:app`, `platform:web` | web or shared libs; React/Next imports are banned |
| `designsystem` | `libs/designsystem` | `type:tokens`, `platform:shared` | nothing |
| `component-library-react` | `libs/componentLibrary/react` | `type:ui`, `platform:react` | tokens |
| `component-library-web` | `libs/componentLibrary/web` | `type:ui`, `platform:web` | tokens |
| `contracts` | `libs/contracts` | `type:contracts`, `platform:shared` | nothing |
| `chat-client` | `libs/chat-client` | `type:data-access`, `platform:shared` | nothing (no React/Next/Lit imports) |
| `modulithplatformservice` | `services/modulithplatformservice` | `type:service`, `platform:jvm` | (Maven) |
| `flyway-migration` | `database/flywayMigration` | `type:migration`, `platform:db` | — |
| `platform-tests` | `tests` | `type:e2e` | contracts |

Rules are enforced by `@nx/enforce-module-boundaries` in `eslint.config.mjs` (apps cannot import apps; React and
Lit code never import each other; only tokens are shared). Naming differences from the setup spec: Nx project names
are kebab-case (`component-library-react`, `flyway-migration`) while folders keep the spec's camelCase names.

Shared library imports go through TypeScript path aliases in `tsconfig.base.json` (integrated workspace,
no per-lib package builds): `@chat-platform/designsystem`, `@chat-platform/designsystem/tokens.css`,
`@chat-platform/component-library-react`, `@chat-platform/component-library-web`.

## Backend: modular monolith

One deployable Spring Boot app (ADR-0002). Each direct sub-package of `com.byteflock.chatplatform` is a Spring
Modulith application module; `ModularityTests` runs `ApplicationModules.verify()` so illegal cross-module access
fails the build. Future product modules (tenancy, websites, conversations, messaging) are added per feature spec,
each with its own package, public API and tables.

Configuration is environment-driven (`application.yml`); `local` profile exposes the Prometheus endpoint. Errors are
RFC 9457 `application/problem+json` without stack traces.

## Data and migrations

- Dedicated Flyway runner image (ADR-0003); the runtime has no Flyway dependency and its DB role cannot run DDL.
- Roles and schema are bootstrapped by `database/postgres-init/01-roles.sh` on first volume initialisation.
- Migrations are immutable once applied; add `V<n>__description.sql`.

## Chat MVP and real-time

Implemented by [specs/mvpsetup.spec.md](../specs/mvpsetup.spec.md); guide: [features/local-chat-mvp.md](features/local-chat-mvp.md).

- Backend modules: `chat` (demo actors, conversations, messages, token authorization) depends on `realtime`
  (Centrifugo token signing + HTTP API publisher). Send path: validate → commit → publish; publish failure after
  commit is reported (`realtimeDelivery: FAILED`), never rolled back. No outbox yet (at-most-once delivery).
- Centrifugo (ADR-0004): `chat` namespace, client publish/subscribe disabled; subscriptions require a backend-issued
  JWT bound to actor + exact channel. Channels `chat:inbox`, `chat:conversation:<uuid>`.
- Frontends share `libs/chat-client` (Nx `type:data-access`, `platform:shared`): typed API client, `mergeMessages`
  (dedupe by id), and Centrifuge wiring — no React or Lit runtime inside, so the React/Lit boundary still holds.
- **LOCAL POC — NOT REAL AUTHENTICATION:** identities are hardcoded (`userone`, `agentbob`). Real authentication,
  tenancy-scoped channels and an outbox are required before any shared environment.

## Deployment boundaries

Each deployable has its own OCI image: `chatdashboardapp` (Next standalone, non-root), `chatwidget` (static bundle
on unprivileged nginx), `modulithplatformservice` (layered jar on Temurin 25 JRE, non-root), `flyway-migration`.
Configuration via environment variables only; no cloud SDKs.

## Deferred projects

| Project | Intended location | Introduce when |
|---|---|---|
| `projectDashboard` | `apps/projectDashboard` | A separate engineering/project-management UI is required by a feature spec; choose its framework then. |
| `bffservice` | `services/bffservice` | The dashboard needs an aggregation/security boundary distinct from the public API (e.g. session-cookie auth, response shaping across modules, rate limiting per UI). |

Neither exists in the workspace or Compose files; nothing depends on them.
