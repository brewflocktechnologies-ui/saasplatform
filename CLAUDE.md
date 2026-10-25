# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Repository rules for all agents live in `AGENTS.md` (spec-first, contract-first, immutable migrations, no destructive
Docker/Git commands). Read it too. Product work starts from a spec in `specs/features/` (template:
`specs/features/_template/feature.spec.md`); the chat MVP spec is `specs/mvpsetup.spec.md`.

## Commands

All JS tasks go through Nx (integrated workspace, pnpm, no per-lib package.json). Run from the repo root.

```bash
pnpm install --frozen-lockfile
pnpm lint | pnpm typecheck | pnpm test | pnpm build     # nx run-many; test/build include the Maven service
pnpm nx test chatwidget                                  # one project (vitest)
pnpm nx test chat-client --testNamePattern=mergeMessages # one test by name (passed through to vitest)
pnpm contracts:validate        # Redocly lint (OpenAPI + AsyncAPI) + strict Ajv compile of json-schema/
pnpm e2e                       # Playwright (tests/), needs infra + backend running
pnpm exec playwright test -c tests/playwright.config.ts -g "exchange messages"   # one E2E test
pnpm verify                    # scripts/verify-local.ps1: full gate incl. runtime/telemetry checks, PASS/FAIL table
```

Backend (Maven Wrapper is the source of truth; Nx calls it via `tools/mvnw.mjs`):

```bash
cd services/modulithplatformservice
./mvnw test                                   # Windows: .\mvnw.cmd test
./mvnw test -Dtest=ChatIntegrationTests       # one class (Testcontainers; needs Docker)
```

The machine default JDK may be newer than 25; the project targets `--release 25` (enforcer `[25,)`). Set `JAVA_HOME`
to a JDK 25 per process when reproducing validation results.

Local stack:

```bash
./startlocalsetup.sh            # full container stack (Mode B), waits for health; ./startlocalsetup.sh --infra = infra only
./stoplocalsetup.sh             # compose down, volumes kept (--pause = stop only)
pnpm dev:backend | pnpm dev:dashboard | pnpm dev:widget  # Mode A hot reload, after infra is up
pnpm db:info | pnpm db:validate | pnpm db:migrate        # Flyway runner via `docker compose run`
```

Mode A and Mode B use the same host ports (3000, 5173, 8080), so stop one before starting the other. Never run
`docker compose down -v` (deletes DB and telemetry volumes) unless asked.

## Architecture

- **Projects** (Nx names ≠ some folder names): `chatdashboardapp` (Next.js 16 App Router, `/agent`),
  `chatwidget` (Lit + Vite multi-page: `/` overview, `/demo` chat, versioned embed `src/embed/v1.ts`),
  `designsystem` (CSS tokens in `libs/designsystem/tokens`), `component-library-react`, `component-library-web`,
  `chat-client` (framework-agnostic API + Centrifuge client), `contracts`, `modulithplatformservice`
  (`services/`), `flyway-migration` (`database/flywayMigration`), `platform-tests` (`tests/`).
- **Boundaries** are enforced by `@nx/enforce-module-boundaries` in `eslint.config.mjs` using `type:*` and
  `platform:*` tags. React and Lit code never import each other; only tokens (`platform:shared`) and `chat-client`
  (`type:data-access`, which bans React/Next/Lit imports) are shared. Libraries are consumed via `tsconfig.base.json`
  path aliases (`@chat-platform/...`), including `@chat-platform/designsystem/tokens.css`.
- **Backend** is a Spring Modulith modular monolith (`com.byteflock.chatplatform`); each top-level package is a
  module, verified by `ModularityTests`. `chat` depends on `realtime`; `shared` is cross-cutting config only.
  Config comes from env vars; in host mode `application.yml` imports the repo-root `.env`
  (`optional:file:../../.env[.properties]`).
- **Migrations** run only through the separate Flyway image as `platform_migrator` (schema owner). The app connects
  as `platform_app` (DML only, roles created by `database/postgres-init/01-roles.sh` on first volume init) and has no
  Flyway runtime dependency. Add `V<n>__*.sql`; never edit an applied migration. Integration tests apply the same SQL
  via test-scoped Flyway into a Testcontainers PostgreSQL (`-Dflyway.sql.dir` set by surefire).
- **Chat send path** (`ChatService.send`): resolve demo actor (role derived server-side) → validate/trim (≤4000) →
  insert + touch conversation in one `TransactionTemplate` → after commit publish `message.created` to
  `chat:conversation:<id>` and `chat:inbox` via Centrifugo HTTP API. Publish failure keeps the message and returns
  `realtimeDelivery: FAILED`. There is no outbox, so clients reload history from the API on every (re)subscribe and
  dedupe by id (`mergeMessages`).
- **Centrifugo** (`infra/centrifugo/config.json`): namespace `chat` denies client subscribe/publish; browsers need
  backend-issued connection and subscription JWTs (`/api/v1/demo/realtime/*`, separate HMAC secrets). The `centrifuge`
  SDK reports server-side subscription denial as `unsubscribed` (code 103), not `error`.
- **Demo identities** `userone`/`agentbob` (`DemoActor`) are a LOCAL POC, NOT authentication. Keep the
  "LOCAL POC — NOT REAL AUTHENTICATION" labelling when touching these flows.
- **Contracts**: `libs/contracts/openapi/platform-api.yaml` references `json-schema/chat/*.schema.json`; each
  schema's `$id` must equal `https://schemas.chat-platform.local/<dir>/<filename>` so relative `$ref`s resolve in both
  Redocly and Ajv. `tests/contract` validates live API responses and real Centrifugo events against them.
- **Browser-facing config** (`PUBLIC_API_BASE_URL`, `PUBLIC_CENTRIFUGO_WS_URL`, `PUBLIC_WIDGET_DEMO_URL`) is read per
  request by Next.js server components (`dynamic = 'force-dynamic'`); it must be `localhost` URLs, never Compose
  hostnames. Secrets never go into `PUBLIC_*`/`VITE_*`.
- **Observability**: the backend sends OTLP traces/logs to the Collector (→ Tempo, Loki); Prometheus scrapes
  `/actuator/prometheus` (exposed only with the `local` profile). Mode A scrapes `host.docker.internal:8080` via
  `infra/observability/prometheus.yml`; Mode B mounts `prometheus.containers.yml` instead (one target per mode). Every
  API response has `X-Trace-Id`. Grafana dashboards are file-provisioned; on Windows, run `docker compose restart grafana`
  after editing them.

## Gotchas

- `.env` is git-ignored and generated (`scripts/dev-setup.ps1` or `startlocalsetup.sh`). Both only append keys newly
  added to `.env.example` and never change existing values. Compose fails fast on missing `:?` variables.
- Next.js `output: 'standalone'` is enabled only when `NEXT_OUTPUT=standalone` (set in its Dockerfile); on Windows,
  standalone tracing fails with symlink error 1314.
- The `platform-tests:e2e` target is defined explicitly (not inferred) so Playwright's `reuseExistingServer` decides
  whether to start the frontends. Playwright transpiles tests as CommonJS, so use `__dirname`, not `import.meta`.
- Shell scripts and `mvnw` need LF endings and the executable bit in Git (`git update-index --chmod=+x`); CI runs
  on Linux.
- `apps/chatdashboardapp/AGENTS.md` is generated by `next dev`; it points to version-matched Next docs in
  `node_modules/next/dist/docs/`. Check those docs rather than relying on memory for Next 16 APIs.
- Docs that must stay in sync with behaviour: `README.md`, `docs/setup/local-development.md`,
  `docs/features/local-chat-mvp.md`, `docs/setup/tool-versions.md` (pinned versions, no `latest`).
