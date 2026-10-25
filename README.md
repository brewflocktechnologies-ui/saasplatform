# Chat Platform

Foundation for a multi-tenant SaaS customer-support / live-chat platform: an Nx monorepo with a Next.js dashboard,
an embeddable Lit widget, shared design tokens and component libraries, a Spring Boot modular monolith, a dedicated
Flyway migration runner, Centrifugo, and a local OpenTelemetry/Prometheus/Grafana/Tempo/Loki stack.

> Status: **local POC**. Includes the end-to-end [local chat MVP](docs/features/local-chat-mvp.md): visitor `userone`
> (widget) and agent `agentbob` (dashboard) chat live via the API, PostgreSQL and Centrifugo.
>
> ⚠️ **LOCAL POC — NOT REAL AUTHENTICATION.** The demo identities are hardcoded and chosen by the client. Nothing here
> is approved for UAT or production. New features arrive through specs in [`specs/features/`](specs/features/README.md).

## One-command start/stop (bash: Git Bash, WSL, macOS, Linux)

```bash
./startlocalsetup.sh            # full container stack (infra + Flyway + backend + dashboard + widget), waits for health
./startlocalsetup.sh --infra    # infrastructure only; then pnpm dev:backend / dev:dashboard / dev:widget
./stoplocalsetup.sh             # remove containers, keep all data volumes
./stoplocalsetup.sh --pause     # just stop containers
```

`startlocalsetup.sh` creates `.env` with random local secrets if missing (or appends newly required keys; existing
values are never changed). Neither script deletes volumes. JS dependencies for host mode/tests still come from
`pnpm install`.

## Quick start (Windows PowerShell)

Prerequisites: Docker Desktop, Node.js 24 LTS with Corepack, JDK 25 (`JAVA_HOME`). Details:
[docs/setup/local-development.md](docs/setup/local-development.md).

```powershell
corepack enable
powershell -ExecutionPolicy Bypass -File scripts/dev-setup.ps1   # creates .env (once) + installs deps
docker compose up -d --wait                                      # infra + Flyway migration
```

**Mode A — hot reload** (one terminal each):

```powershell
pnpm dev:backend     # Spring Boot  http://localhost:8080
pnpm dev:dashboard   # Next.js      http://localhost:3000
pnpm dev:widget      # Lit + Vite   http://localhost:5173
```

(`pnpm dev` starts all three through Nx in one terminal.)

**Mode B — full container POC** (stop Mode A first; same ports):

```powershell
docker compose -f docker-compose.yml -f docker-compose.apps.yml config -q
pnpm stack:up        # = docker compose -f docker-compose.yml -f docker-compose.apps.yml up --build -d --wait
docker compose -f docker-compose.yml -f docker-compose.apps.yml ps
pnpm stack:down      # stops and removes containers, KEEPS data volumes
```

## URLs

| Service | URL |
|---|---|
| Dashboard | http://localhost:3000 · **agent inbox (agentbob): http://localhost:3000/agent** |
| Widget | http://localhost:5173 · **visitor chat (userone): http://localhost:5173/demo** |
| Backend API | http://localhost:8080/api/v1/status · /api/v1/bootstrap · /api/v1/demo/* · /actuator/health |
| Centrifugo | WebSocket `ws://localhost:8000/connection/websocket`, health http://localhost:8000/health |
| Grafana | http://localhost:3001 (admin password in your local `.env`) |
| Prometheus | http://localhost:9090 |
| Loki / Tempo | http://localhost:3100 / http://localhost:3200 |
| OTel Collector | OTLP gRPC `localhost:4317`, HTTP `localhost:4318`, health http://localhost:13133 |
| PostgreSQL | `127.0.0.1:5432`, db `chatplatform` |

All host ports bind to `127.0.0.1`. The authoritative port map is [.env.example](.env.example).

## Common commands (all tested)

```powershell
pnpm install --frozen-lockfile
pnpm nx show projects
pnpm lint; pnpm typecheck; pnpm test; pnpm build   # Nx run-many (test/build include the Maven service)
pnpm contracts:validate                            # Redocly lint (OpenAPI + AsyncAPI) + strict JSON Schema compile
pnpm db:info; pnpm db:validate; pnpm db:migrate    # Flyway runner (docker compose run)
pnpm e2e                                           # Playwright: smoke, two-browser chat, live API/event contracts (stack must be up)
docker compose -f docker-compose.yml -f docker-compose.apps.yml logs -f modulithplatformservice centrifugo postgres
pnpm verify                                        # everything above + health/telemetry checks, PASS/FAIL summary
```

Maven directly:

```powershell
Push-Location services/modulithplatformservice
.\mvnw.cmd test
.\mvnw.cmd package
Pop-Location
```

## Layout

```text
apps/chatdashboardapp        Next.js dashboard (Nx project: chatdashboardapp)
apps/chatwidget              Lit widget + demo page (chatwidget)
libs/designsystem            CSS design tokens (designsystem)
libs/componentLibrary/react  React components (component-library-react)
libs/componentLibrary/web    Lit web components (component-library-web)
libs/contracts               OpenAPI, AsyncAPI, JSON Schema (contracts)
libs/chat-client             framework-agnostic chat API + Centrifuge client (chat-client)
services/modulithplatformservice  Spring Boot modular monolith (modulithplatformservice)
database/flywayMigration     Flyway runner image + SQL (flyway-migration)
database/postgres-init       one-time role/schema bootstrap for the local DB
tests                        Playwright E2E + contract tests (platform-tests)
infra                        Centrifugo + observability config
specs                        feature specs, ADRs, architecture notes
docs                         setup, runbooks, architecture
```

More: [architecture](docs/architecture.md) · [ADRs](specs/decisions/) · [runbook](docs/runbooks/local-stack.md) ·
[troubleshooting](docs/setup/troubleshooting.md) · [validation report](docs/setup/validation-report.md) ·
[contributing](CONTRIBUTING.md) · [agent rules](AGENTS.md).

## Workspace creation (for reference)

```powershell
npx create-nx-workspace@24.0.1 chat-platform --preset=apps --workspaces=false --pm=pnpm --nxCloud=skip `
  --skipGit --formatter=prettier --linter=eslint --aiAgents=none --analytics=false --interactive=false
pnpm nx add @nx/next; pnpm nx add @nx/react; pnpm nx add @nx/vite; pnpm nx add @nx/web
pnpm nx add @nx/playwright; pnpm nx add @nx/eslint
```

The Spring Boot project was generated with start.spring.io (Boot 4.1.2, Java 25, Maven).
