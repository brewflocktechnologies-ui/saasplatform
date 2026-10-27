# Workspace inventory

Phase 0 artifact of the conversation-platform plan set (master spec section 2, "Workspace and component inventory"). Later plans cite this file instead of re-discovering the workspace.

- **Date:** 2026-10-27. Machine local time is +0530 (`JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Kolkata`); UTC is used where a timestamp matters.
- **Method:** read-only. Nothing was built, started, stopped, cleaned, reset or edited. Commands run: version commands, `git` read commands, `find`, file reads, HTTP `GET` health probes, `netstat`, `docker compose ps`, and one read-only `psql` session (SELECT statements and `\dt`) inside the already-running `postgres` container. The only deliverable files written are `docs/research/workspace-inventory.md` and `docs/research/reference-snapshot.txt`.
- **Convention:** a plain statement is *observed* (a command run or a file read in this task; the path or command is named). **Inference** marks my reading of observed facts. **Documented, not re-run** marks a claim taken from a project document that this task did not re-verify (test results in particular: no test suite was executed). **Not found** is a finding, not a gap in the inventory.
- **External documentation consulted:** none (all sources are repository files).
- **Secrets:** only `.env` key names appear here. `.env.example` placeholders and the allocation engine's fake local defaults are quoted; no generated value is.
- **Planned vs existing:** items under "Planned" are decisions from the plan overview. They do not exist yet.

Paths are relative to `D:\BYTEFLOCK\saasplatform` unless they start with a drive letter or `allocation-engine/` (= `D:\BYTEFLOCK\RESEARCH\Allocationservice-research\allocation-engine`).

## 1. Toolchain on this machine

| Command | Output |
|---|---|
| `docker --version` | `Docker version 29.9.2, build a82e528` |
| `docker compose version` | `Docker Compose version v5.6.1` |
| `docker info --format ...` | `Server 29.9.2 \| Docker Desktop` (daemon reachable; Docker Desktop was already running, so nothing had to be started) |
| `node --version` | `v24.18.0` |
| `pnpm --version` | `10.11.0` |
| `"C:/Program Files/Java/jdk-25.0.3/bin/java" -version` | `java version "25.0.3" 2026-04-21 LTS` / `Java(TM) SE Runtime Environment (build 25.0.3+9-LTS-195)` / `Java HotSpot(TM) 64-Bit Server VM (build 25.0.3+9-LTS-195, mixed mode, sharing)` |
| `java -version` (machine default) | `java version "27" 2026-09-15` / `Java(TM) SE Runtime Environment (build 27+35-2325)` / `Java HotSpot(TM) 64-Bit Server VM (build 27+35-2325, mixed mode, sharing)` |
| `git --version` | `git version 2.54.0.windows.1` |

- `JAVA_HOME` defaults to `C:\Program Files\Java\jdk-27`. `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Kolkata` is set, so every JVM prints a `Picked up JAVA_TOOL_OPTIONS ...` line on stderr (noise; filtered from the version lines above).
- JDK pinning differs by project. Platform backend: `maven-enforcer` `requireJavaVersion [25,)` (`services/modulithplatformservice/pom.xml`), so JDK 27 builds it. Allocation engine: `requireJavaVersion [25,26)` (`allocation-engine/backend/pom.xml`), so it fails on JDK 27 and every Maven command needs `JAVA_HOME="C:/Program Files/Java/jdk-25.0.3"`.
- **Docker state at check time** (`docker compose ps -a`, project `chat-platform`): `postgres`, `centrifugo`, `prometheus`, `grafana` healthy; `otel-collector`, `tempo`, `loki` up; `flyway-migration` exited 0. The three application containers (`modulithplatformservice`, `chatdashboardapp`, `chatwidget`) are not running: `docker ps` lists only the seven infrastructure containers and nothing listens on 3000, 5173 or 8080 (infrastructure stack only). Loki `/ready` returned 503 in the first minutes after start and 200 on re-check. This state is transient. The infrastructure stack was started by the coordinating agent (`./startlocalsetup.sh --infra`, infrastructure only), not by this task; this task started, stopped and modified no container and only read from them (`docker ps`, `docker compose ps -a`, health probes, one read-only `psql` session).
- **Listening ports at check time** (`netstat -ano`): 3001, 3100, 3200, 4317, 4318, 5432, 8000, 9090, 13133 on 127.0.0.1. **No listener** on 3000, 5173, 8080, 8081 or 5433.

## 2. Repositories and git state (spec 2.1)

### 2.1 Layout

```text
D:\BYTEFLOCK                              git root #1: branch main, NO commits, no remote
  .superpowers\sdd\                       plan-execution ledgers (own .gitignore: "*")
  saasplatform\                           git root #2 (its own .git): the platform
  RESEARCH\Allocationservice-research\
    .gitignore                            *.zip, Rocket.Chat-develop/, chatwoot-develop/, livehelperchat-master/
    allocation-engine\                    inside git root #1 (no .git of its own), entirely untracked
    livechat-conversation-platform-research-and-build-spec.md    master spec (untracked)
    allocation-engine-research-and-build-spec.md                 engine spec (untracked)
    chatwoot-develop\  Rocket.Chat-develop\  zulip-main\zulip-main\  livehelperchat-master\    zip snapshots, no .git
    chatwoot-develop.zip  Rocket.Chat-develop.zip  zulip-main.zip  livehelperchat-master.zip
```

"Find the checkouts by repository URL" (spec 2.1) is **not possible**: the four reference checkouts have no `.git` and so no remote. They are identified by folder name plus manifest content and by the SHA-256 of their zip (section 7).

### 2.2 `D:\BYTEFLOCK` (git root #1)

- Path / toplevel: `D:/BYTEFLOCK` (`git rev-parse --show-toplevel`). Remote: none (`git remote -v` prints nothing). Branch: `main`. `HEAD`: none (`git log` -> `fatal: your current branch 'main' does not have any commits yet`; `git ls-files` is empty).
- `git status --short`: `?? RESEARCH/` and `?? saasplatform/` (`saasplatform/` shows as an untracked nested repository). With `-uall`, about 9,750 untracked paths are listed outside `saasplatform/`: 9,020 under `zulip-main/`, 725 under `allocation-engine/`, 3 others (the nested `.gitignore` and the two spec files).
- **`.gitignore`: there is no `D:\BYTEFLOCK\.gitignore`.** The ignore file is the nested `RESEARCH/Allocationservice-research/.gitignore` (4 patterns above). It does **not** list `zulip-main/`, which is why zulip files appear as untracked (section 9, finding 1). The engine's `HANDOVER.md` says the root `.gitignore` excludes the zips and checkouts; that is not what is on disk.
- Licence: none. Language/runtime: none (container directory).
- Local modifications that must not be overwritten: the whole of `allocation-engine/` (never committed, so the working tree is the only copy), both spec files, the `.superpowers/sdd/` ledgers.

### 2.3 `D:\BYTEFLOCK\saasplatform` (git root #2): the platform

- Path / toplevel: `D:/BYTEFLOCK/saasplatform`.
- `git remote -v`: `origin https://github.com/brewflocktechnologies-ui/saasplatform.git (fetch)` and `(push)`.
- Branch: `feat/conversation-platform`. `HEAD`: `57ce803fd22c161c29f6dd1f023457fe10cd4ad7` ("docs(plans): conversation platform plan set (overview, plans 01-07, kickoff)"). That commit is the only one ahead of `main` (`a5a082e`, which equals `origin/main` in the local refs). 6 commits are reachable from `HEAD`; 229 tracked files at `57ce803`; no stashes.
- `git status --short` before any change: empty (clean). Ignored but present on disk: `.env`, `.nx/`, `.superpowers/`, `apps/chatdashboardapp/.next/`, `apps/chatdashboardapp/tsconfig.tsbuildinfo`, `dist/`, `node_modules/`, `services/modulithplatformservice/target/`.
- Licence: **no LICENSE file in the repository root** (not found). `package.json` says `"license": "MIT"`; `libs/contracts/openapi/platform-api.yaml` says `Proprietary` / `LicenseRef-Proprietary` (section 9, finding 6). No enterprise or paid-licence directories.
- Runtime and dependency versions (from `package.json`, `services/modulithplatformservice/pom.xml`, `docs/setup/tool-versions.md`): Node `>=24` / pnpm 10.11.0 (`packageManager`) / Nx 24.0.1 / TypeScript ~6.0.3 / Next.js ~16.4.3 with React ^19 / Lit 3.3.3 / Vite ^8 / Vitest ~5.0.1 / `centrifuge` 5.8.0 (the installed `node_modules/centrifuge` is also 5.8.0) / Playwright 1.64.1; Java 25 / Spring Boot 4.1.2 / Spring Modulith 2.1.2 / Maven Wrapper (Maven 3.10.0 per tool-versions, documented, not re-run) / Nimbus JOSE JWT 10.10; images `postgres:18.6-alpine3.24`, `flyway/flyway:13.11.1-alpine`, `centrifugo/centrifugo:v6.10.2`, `otel/opentelemetry-collector-contrib:0.163.2`, `prom/prometheus:v3.15.1`, `grafana/grafana:13.2.4`, `grafana/tempo:3.1.1`, `grafana/loki:3.7.9`.
- Build, test, lint and run commands (from `package.json`, `README.md`, `CLAUDE.md`; not re-run here): `pnpm install --frozen-lockfile`, `pnpm lint | typecheck | test | build` (Nx `run-many`; `test` and `build` include the Maven service), `pnpm contracts:validate`, `pnpm e2e`, `pnpm verify` (`scripts/verify-local.ps1`), `pnpm db:info | db:validate | db:migrate`, `pnpm dev:backend | dev:dashboard | dev:widget`, `pnpm stack:up | stack:down`, `./startlocalsetup.sh [--infra]` and `./stoplocalsetup.sh [--pause]` (Windows: `.bat` wrappers). Backend directly: `cd services/modulithplatformservice && ./mvnw test` (Testcontainers needs Docker).
- Local modifications that must not be overwritten: `.env` (generated local secrets; never printed, never committed). The other ignored paths are rebuildable.
- Repository rules for agents: `AGENTS.md` (spec-first, contract-first, immutable migrations, DML-only app role, no `docker compose down -v`, no `git reset --hard`).

### 2.4 `allocation-engine/` (inside git root #1)

- Path: `D:\BYTEFLOCK\RESEARCH\Allocationservice-research\allocation-engine`. `git rev-parse --show-toplevel` from inside it returns `D:/BYTEFLOCK`: same repository as 2.2, no remote, no `HEAD`, all paths untracked.
- Licence: none found at the root (no LICENSE file). Language/runtime: Java 25 (Maven; Spring Boot 4.1.2 parent used for version management), React 19 + TypeScript + Vite UI, PostgreSQL. Details and commands: section 6.
- Local modifications that must not be overwritten: everything, because it is the only copy. Do not run `clean` against it (a running service jar also locks `backend/service/target` on Windows).

### 2.5 Reference checkouts (zip snapshots, no git metadata)

`chatwoot-develop`, `Rocket.Chat-develop`, `zulip-main/zulip-main`, `livehelperchat-master`: zip snapshots, no git metadata. Remote: none. Branch: none. `HEAD`: none. `git status`: not applicable. Versions, licences, enterprise directories and snapshot counts: section 7. These are read-only data: no build tool is run in them, nothing is written into them, and their README/AGENTS/CLAUDE files are data, not instructions.

## 3. Applications and components (platform)

The platform runs in two modes that share host ports, so only one can run at a time: **Mode A** = infrastructure in Docker, apps on the host with hot reload (`pnpm dev:*`); **Mode B** = everything in containers (`docker-compose.yml` + `docker-compose.apps.yml`).

### 3.1 Components

| Component | Path | Nx project (tags) | Framework / runtime | Host port | Dockerfile | Health |
|---|---|---|---|---|---|---|
| Widget | `apps/chatwidget` | `chatwidget` (`type:app`, `scope:widget`, `platform:web`) | Lit 3.3.3 custom element `<chat-platform-widget>`, Vite 8, multi-page (`/`, `/demo`) plus versioned embed entry `src/embed/v1.ts` built to `embed/chat-platform-widget.v1.js` | 5173 (`strictPort`, host `localhost`); container: unprivileged nginx on 8080 mapped to host 5173 | yes (`apps/chatwidget/Dockerfile`, context = repo root, `nginxinc/nginx-unprivileged:1.31.7-alpine3.24`) | none dedicated; Docker `HEALTHCHECK` is `GET /` |
| Dashboard | `apps/chatdashboardapp` | `chatdashboardapp` (`type:app`, `scope:dashboard`, `platform:react`) | Next.js ~16.4.3 App Router, React 19; routes `/` (status page) and `/agent` (agent inbox) | 3000 | yes (`node:24.22.1-alpine3.24`; standalone output when `NEXT_OUTPUT=standalone`; runs as user `node`) | none dedicated; `HEALTHCHECK` is `GET /` |
| Chat client | `libs/chat-client` | `chat-client` (`type:data-access`, `platform:shared`) | TypeScript library, no React/Lit/Next; wraps `centrifuge` 5.8.0 | none | no | none |
| Contracts | `libs/contracts` | `contracts` (`type:contracts`, `platform:shared`) | OpenAPI 3.1.0 (`info.version` 0.2.0), AsyncAPI 3.0.0 (0.2.0), 4 JSON Schema 2020-12 files; Redocly CLI ^2.71.0, Ajv | none | no | none |
| Backend | `services/modulithplatformservice` | `modulithplatformservice` (`type:service`, `platform:jvm`; implicit dependency `flyway-migration`) | Spring Boot 4.1.2, Java 25, Spring Modulith 2.1.2, Spring MVC, `JdbcClient`, Actuator, OpenTelemetry starter, Micrometer Prometheus. Modules (packages under `com.byteflock.chatplatform`): `bootstrap`, `chat`, `health`, `realtime`, `shared` | 8080 (`SERVER_PORT`) | yes (`eclipse-temurin:25.0.4.1_1-jre-alpine-3.24`, layered jar, user `app`) | `/actuator/health`, `/actuator/health/readiness` (Docker `HEALTHCHECK`), `/api/v1/status` |
| Flyway runner | `database/flywayMigration` | `flyway-migration` (`type:migration`, `platform:db`) | `flyway/flyway:13.11.1-alpine` with the `sql/` directory (V1, V2) copied in; one-shot job | none | yes | exits 0 on success (`flyway-migration` shows `Exited (0)`) |
| Platform tests | `tests` | `platform-tests` (`type:e2e`; implicit dependencies `chatdashboardapp`, `chatwidget`, `modulithplatformservice`, `contracts`) | Playwright 1.64.1 (Chromium), `pg` 8.23.1 for a read-only DB assertion | none | no | none |
| Centrifugo | `infra/centrifugo/config.json` | none (image only) | `centrifugo/centrifugo:v6.10.2` | 8000 | image | `GET /health` (compose healthcheck uses `wget`) |
| PostgreSQL | `database/postgres-init/01-roles.sh` (init script) | none (image only) | `postgres:18.6-alpine3.24`, named volume `postgres-data` | 5432 | image | `pg_isready` |
| Observability | `infra/observability/` | none (images only) | OTel Collector contrib 0.163.2, Prometheus v3.15.1, Tempo 3.1.1, Loki 3.7.9, Grafana 13.2.4 | see 3.3 | images | Collector `http://localhost:13133/`; Prometheus `/-/ready`; Tempo `/ready`; Loki `/ready`; Grafana `/api/health` |
| Design system and component libraries (not in the brief's list) | `libs/designsystem`, `libs/componentLibrary/react`, `libs/componentLibrary/web` | `designsystem`, `component-library-react`, `component-library-web` | CSS design tokens; React and Lit component libraries | none | no | none |

- **Not present:** `apps/projectDashboard` and `services/bffservice` (documented as deferred in `docs/architecture.md`; neither exists in the workspace or Compose files). No allocation client or module exists in the platform (section 8, boundary 6).
- Boundaries are enforced by `@nx/enforce-module-boundaries` in `eslint.config.mjs` (React and Lit code never import each other; only tokens and `chat-client` are shared). Libraries resolve through `tsconfig.base.json` path aliases (`@chat-platform/...`); there are no per-library builds.

### 3.2 Commands

Nx targets for the widget, dashboard and libraries are inferred by the plugins listed in `nx.json` (`@nx/vite`, `@nx/next`, `@nx/vitest`, `@nx/eslint`, `@nx/js/typescript`, `@nx/playwright`) rather than written in `project.json`. The names below come from `README.md`, `CLAUDE.md`, `package.json` and `project.json`; `nx show project` was not run (it writes to `.nx/`). Test-case counts are `@Test` / `it(` / `test(` occurrences in source, **not** results: no suite was executed.

| Component | dev | build | test | other |
|---|---|---|---|---|
| Widget | `pnpm dev:widget` (`nx run chatwidget:dev`) | `pnpm nx build chatwidget` -> `dist/apps/chatwidget` | `pnpm nx test chatwidget` (Vitest + jsdom; 5 cases) | `pnpm nx run chatwidget:preview` serves the production bundle on 5173 |
| Dashboard | `pnpm dev:dashboard` (`nx run chatdashboardapp:dev`) | `pnpm nx build chatdashboardapp` | `pnpm nx test chatdashboardapp` (Vitest; 3 cases) | `nx run chatdashboardapp:start`; `typecheck` is defined explicitly in `project.json` |
| Chat client | none | none (path alias only) | `pnpm nx test chat-client` (Vitest; 6 cases) | lint, typecheck |
| Contracts | none | none | none | `pnpm contracts:validate` = `redocly lint libs/contracts/openapi/platform-api.yaml libs/contracts/asyncapi/chat-realtime.yaml --config libs/contracts/redocly.yaml && node tools/validate-json-schemas.mjs` |
| Backend | `pnpm dev:backend` = `node ../../tools/mvnw.mjs spring-boot:run -Dspring-boot.run.profiles=local` (cwd = service dir) | `nx build modulithplatformservice` = `mvnw package -DskipTests` | `nx test modulithplatformservice` = `mvnw test` (28 `@Test` in 7 classes; Testcontainers need Docker) | `docker-build` target |
| Flyway runner | none | `nx run flyway-migration:docker-build` | none | `pnpm db:info`, `db:validate`, `db:migrate` = `docker compose run --rm --build flyway-migration <cmd>` |
| Platform tests | none | none | `pnpm e2e` = `playwright test -c tests/playwright.config.ts` (10 browser cases + 9 live contract cases) | needs infrastructure and backend up; reuses servers already on 3000/5173, otherwise starts `chatdashboardapp:start` and `chatwidget:preview` |
| Whole stack | `./startlocalsetup.sh --infra` then `pnpm dev:*` | `pnpm stack:up` (`docker compose -f docker-compose.yml -f docker-compose.apps.yml up --build -d --wait`) | `pnpm verify` | `docker compose up -d --wait` (infrastructure only) |

### 3.3 Ports

All host ports bind to `127.0.0.1`. The authoritative map is `.env.example` (comment in the file). A change to the dashboard or widget port also needs `PUBLIC_WIDGET_DEMO_URL`, `APP_CORS_ALLOWED_ORIGINS` and `infra/centrifugo/config.json` `allowed_origins` updated (`docs/setup/local-development.md`).

| Port | Service | Variable | Listening at check time |
|---|---|---|---|
| 3000 | Dashboard | `DASHBOARD_HOST_PORT` | no (apps not running) |
| 5173 | Widget | `WIDGET_HOST_PORT` | no |
| 8080 | Backend | `BACKEND_HOST_PORT` / `SERVER_PORT` | no |
| 8000 | Centrifugo | `CENTRIFUGO_HOST_PORT` | yes |
| 5432 | PostgreSQL | `POSTGRES_HOST_PORT` | yes |
| 3001 | Grafana (container 3000) | `GRAFANA_HOST_PORT` | yes |
| 9090 | Prometheus | `PROMETHEUS_HOST_PORT` | yes |
| 3100 | Loki | `LOKI_HOST_PORT` | yes |
| 3200 | Tempo | `TEMPO_HOST_PORT` | yes |
| 4317 / 4318 / 13133 | OTel Collector gRPC / HTTP / health | `OTEL_GRPC_HOST_PORT`, `OTEL_HTTP_HOST_PORT`, `OTEL_HEALTH_HOST_PORT` | yes |
| 8081 | **Planned:** allocation service | (engine variable `ALLOCATION_PORT`) | no, free |
| 5433 | **Planned:** allocation service PostgreSQL | (engine variable `ALLOCATION_DB_PORT`) | no, free |

Tempo's own OTLP ports 4317/4318 are internal to the Compose network (documented); only the Collector publishes them on the host.

### 3.4 Environment variables (names only)

- **Root `.env` / `.env.example`** (30 keys; `.env` has exactly the same key set, checked by diffing names). Generated secrets (value `__generate__` in the example): `POSTGRES_ADMIN_PASSWORD`, `POSTGRES_MIGRATOR_PASSWORD`, `POSTGRES_APP_PASSWORD`, `CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY`, `CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY`, `CENTRIFUGO_HTTP_API_KEY`, `GRAFANA_ADMIN_PASSWORD`. Non-secret: `COMPOSE_PROJECT_NAME=chat-platform`, `POSTGRES_DB=chatplatform`, `POSTGRES_ADMIN_USER=platform_admin`, `POSTGRES_MIGRATOR_USER=platform_migrator`, `POSTGRES_APP_USER=platform_app`, `GRAFANA_ADMIN_USER=admin`, `APP_ENVIRONMENT=local`, `PUBLIC_API_BASE_URL=http://localhost:8080`, `PUBLIC_WIDGET_DEMO_URL=http://localhost:5173`, `PUBLIC_CENTRIFUGO_WS_URL=ws://localhost:8000/connection/websocket`, `APP_CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5173`, and the 12 `*_HOST_PORT` keys in 3.3.
- **Backend** (`services/modulithplatformservice/src/main/resources/application.yml`): `DB_URL` (default `jdbc:postgresql://localhost:${POSTGRES_HOST_PORT:5432}/${POSTGRES_DB:chatplatform}?currentSchema=platform`), `POSTGRES_HOST_PORT`, `POSTGRES_DB`, `POSTGRES_APP_USER`, `POSTGRES_APP_PASSWORD`, `SERVER_PORT` (8080), `APP_ENVIRONMENT`, `APP_CORS_ALLOWED_ORIGINS`, `CENTRIFUGO_URL` (default `http://localhost:8000`; `http://centrifugo:8000` in Mode B), `CENTRIFUGO_HTTP_API_KEY`, `CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY`, `CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY`, `CENTRIFUGO_TOKEN_TTL` (default `5m`), `LOG_FORMAT` (default `ecs`), `TRACING_SAMPLING_PROBABILITY` (default 1.0), `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318`), `SPRING_PROFILES_ACTIVE` (Mode B sets `local`). In host mode `application.yml` imports the repo-root `.env` (`optional:file:../../.env[.properties]`). `application-local.yml` only adds `prometheus` to the exposed actuator endpoints.
- **Dashboard** (read per request by server components; `export const dynamic = 'force-dynamic'`): `PUBLIC_API_BASE_URL`, `PUBLIC_WIDGET_DEMO_URL`, `PUBLIC_CENTRIFUGO_WS_URL`, `APP_ENVIRONMENT`. Build/image only: `NEXT_OUTPUT`, `NEXT_TELEMETRY_DISABLED`, `PORT`, `HOSTNAME`.
- **Widget:** build-time `VITE_PUBLIC_API_BASE_URL`, `VITE_PUBLIC_CENTRIFUGO_WS_URL`; element attributes `api-base-url`, `realtime-url`. The Mode B widget container receives no environment, so it uses the in-code defaults `http://localhost:8080` and `ws://localhost:8000/connection/websocket`.
- **Flyway runner:** `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD` (from Compose); image defaults `FLYWAY_LOCATIONS=filesystem:/flyway/sql`, `FLYWAY_SCHEMAS=platform`, `FLYWAY_CONNECT_RETRIES=10`, `FLYWAY_VALIDATE_MIGRATION_NAMING=true`, `REDGATE_DISABLE_TELEMETRY=true`.
- **PostgreSQL container:** `POSTGRES_DB`, `POSTGRES_USER` (= `POSTGRES_ADMIN_USER`), `POSTGRES_PASSWORD`, `POSTGRES_MIGRATOR_USER/PASSWORD`, `POSTGRES_APP_USER/PASSWORD` (consumed by `01-roles.sh`).
- **Centrifugo container:** `CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY`, `CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY`, `CENTRIFUGO_HTTP_API_KEY`.
- **Grafana container:** `GF_SECURITY_ADMIN_USER`, `GF_SECURITY_ADMIN_PASSWORD`, `GF_AUTH_ANONYMOUS_ENABLED`, `GF_ANALYTICS_REPORTING_ENABLED`, `GF_ANALYTICS_CHECK_FOR_UPDATES`.
- **Tests:** `DASHBOARD_URL`, `WIDGET_URL`, `API_URL`, `CENTRIFUGO_WS_URL`, `CI`; `tests/support/db.ts` also reads `POSTGRES_HOST_PORT`, `POSTGRES_DB`, `POSTGRES_APP_USER`, `POSTGRES_APP_PASSWORD` from `.env`.

## 4. Current API and realtime contracts

### 4.1 REST (`libs/contracts/openapi/platform-api.yaml`: OpenAPI 3.1.0, `info.version` 0.2.0, server `http://localhost:8080`, `security: []`)

6 paths, 8 operations. Every `/api/v1/demo/*` operation is a LOCAL POC (section 4.8).

| Method and path | operationId | Input | Responses | Behaviour |
|---|---|---|---|---|
| `POST /api/v1/demo/conversations` | `ensureDemoConversation` | no body, no actor | 200 reused, 201 created | get-or-create the single OPEN conversation of `userone`, `assigned_agent_id` fixed to `agentbob`; race-safe through the partial unique index; publishes `conversation.created` to `chat:inbox` when it created one |
| `GET /api/v1/demo/conversations` | `listAgentConversations` | `actorId` (enum `userone`, `agentbob`) | 200, 400, 403 | agent inbox ordered by `COALESCE(last_message_at, created_at) DESC, id DESC`; `lastMessagePreview` = first 120 characters of the newest message; no pagination |
| `GET /api/v1/demo/conversations/{conversationId}/messages` | `listMessages` | `actorId`; `conversationId` UUID | 200, 400, 403, 404 | all messages ordered by `created_at, id`; no pagination or cursor |
| `POST /api/v1/demo/conversations/{conversationId}/messages` | `sendMessage` | body `{actorId, text}` | 201 `{message, realtimeDelivery: PUBLISHED \| FAILED}`; 400, 403, 404, 503 | text stripped, 1 to 4000 code points; no client-supplied message id or idempotency key |
| `GET /api/v1/demo/realtime/connection-token` | `getConnectionToken` | `actorId` | 200 `{token, expiresAt}`, 400 | see 4.4 |
| `GET /api/v1/demo/realtime/subscription-token` | `getSubscriptionToken` | `actorId`, `channel` | 200, 400, 403, 404 | see 4.4 |
| `GET /api/v1/status` | `getStatus` | none | 200 | `{application, version, environment, status UP \| DEGRADED, timestamp, dependencies: {database, centrifugo}}` |
| `GET /api/v1/bootstrap` | `getBootstrap` | none | 200, 404, 503 | latest row of `platform_bootstrap` |

- Errors are RFC 9457 `application/problem+json` (`Problem` schema); `ApiExceptionHandler` maps `DataAccessException` to 503 without messages or stack traces; every response carries `X-Trace-Id` (`TraceIdResponseFilter`).
- Component schemas: `DependencyStatus`, `StatusResponse`, `BootstrapRecord`, `Conversation`, `ChatMessage`, `SendMessageRequest`, `SendMessageResponse`, `RealtimeToken`, `Problem`.
- Outside OpenAPI, unauthenticated: `/actuator/health`, `/actuator/health/readiness`, `/actuator/info`, and `/actuator/prometheus` (profile `local` only).
- Java side: controllers `chat/ChatController.java`, `chat/RealtimeTokenController.java`, `health/StatusController.java`, `bootstrap/BootstrapController.java`; DTO records in `chat/ChatDtos.java`.

### 4.2 Realtime contract (`libs/contracts/asyncapi/chat-realtime.yaml`: AsyncAPI 3.0.0, 0.2.0; server `ws://localhost:8000/connection/websocket`)

| Channel | Events | Who may subscribe (enforced when the backend signs the subscription token) |
|---|---|---|
| `chat:conversation:<uuid>` | `message.created` | `userone` for their own conversation; `agentbob` for conversations assigned to them |
| `chat:inbox` | `conversation.created`, `message.created` (a refresh signal; clients refetch the inbox from REST) | `agentbob` only |

- Payloads (`libs/contracts/json-schema/chat/*.v1.schema.json`, `additionalProperties: false`): `message-created` = `{type: "message.created", conversationId, message}`; `conversation-created` = `{type: "conversation.created", conversation}`; `message` = `{id (uuid), conversationId, senderId (1-80), senderRole VISITOR | AGENT, body (1-4000), createdAt}`. The TypeScript mirror is `libs/chat-client/src/lib/types.ts` (`Conversation` adds `visitorId`, `assignedAgentId`, `status OPEN | CLOSED`, `createdAt`, `updatedAt`, `lastMessageAt?`, `lastMessagePreview?`).
- **Publish format.** `RealtimePublisher` (`realtime/RealtimePublisher.java`) sends `POST {CENTRIFUGO_URL}/api/publish` with header `X-API-Key` and body `{"channel": ..., "data": <event object>}`; 2 s connect and request timeout; no retry; returns `false` on any non-200 or error result and never throws. Counter `chat.realtime.publications{outcome=success|failure}`. After a message commit it publishes the same `message.created` event to `chat:conversation:<id>` and then to `chat:inbox`; `conversation.created` goes to `chat:inbox` only. The inbox publish result is ignored.
- **Delivery semantics.** Publish-after-commit, at-most-once: no outbox, no retry, no sequence numbers, no Centrifugo channel history or recovery configured. Clients reload history from REST on every (re)subscribe and de-duplicate by message id (`mergeMessages`). If the publish fails the message stays saved and the response says `realtimeDelivery: FAILED`.

### 4.3 Centrifugo configuration (`infra/centrifugo/config.json`, v6.10.2)

| Setting | Value |
|---|---|
| `log.level` | `info` |
| `http_server.port` | `"8000"` |
| `client.allowed_origins` | `["http://localhost:3000", "http://localhost:5173"]` (static file; not driven by `.env`) |
| `client.subscription_token.enabled` | `true` |
| `channel.namespaces` | one namespace, `{"name": "chat"}`, with **no options set** |
| `admin.enabled` | `false` |
| `health.enabled` | `true` |
| secrets | from environment, never in the file: `CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY`, `CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY`, `CENTRIFUGO_HTTP_API_KEY` |

- With no namespace options, client subscribe and client publish are denied (Centrifugo default; stated in `docs/setup/tool-versions.md` and ADR-0004, **documented, not re-run**), and history, recovery and presence are off (**inference** from their absence in the file). There is no `engine` key, so the engine is the in-memory default and Centrifugo is single-node (`docs/features/local-chat-mvp.md`, limitation 4).
- All chat channels sit in namespace `chat` (`chat:inbox`, `chat:conversation:<uuid>`). The helper `ChatChannels` (`chat/ChatChannels.java`) validates the channel format with a regex.

### 4.4 How tokens are issued

- `RealtimeTokenIssuer` (`realtime/RealtimeTokenIssuer.java`): HS256 JWTs signed with Nimbus `MACSigner`. **Connection token** claims: `sub` (the actor id), `iat`, `exp`. **Subscription token** claims: the same plus `channel`. The two tokens use **different secrets** (`CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY` and `CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY`). TTL is `CENTRIFUGO_TOKEN_TTL`, default `5m`. Startup fails if a secret is shorter than 32 characters. No `iss`, `aud`, `info` or tenant claim.
- `RealtimeTokenController` (`chat/RealtimeTokenController.java`): `connection-token` signs a token for either of the two demo actors and checks nothing else. `subscription-token`: `agentbob` with `chat:inbox` is allowed; `userone` with `chat:inbox` is 403; for `chat:conversation:<uuid>` it calls `ChatService.authorize` (404 unknown conversation; 403 unless the agent equals `assigned_agent_id` or the visitor equals `visitor_id`); any other channel string is 400.
- Refresh: both token types expire after the TTL; the browser SDK calls the supplied `getToken` callbacks (4.5) whenever it needs a fresh token. The exact refresh timing is SDK behaviour and was not verified in this task.

### 4.5 How the widget and dashboard subscribe (`libs/chat-client/src/lib/realtime.ts`)

- `createRealtime({url, api, actorId, onState})` builds `new Centrifuge(url, {getToken})` where `getToken` calls `GET /api/v1/demo/realtime/connection-token?actorId=` through `libs/chat-client/src/lib/api.ts`. `subscribe(channel, handlers)` creates `client.newSubscription(channel, {getToken})` with its own `getToken` calling `.../subscription-token?actorId=&channel=`, and wires: `publication` -> `onEvent(ctx.data as ChatEvent)`; `subscribed` -> `onSubscribed()`; `error` -> `onError`; `unsubscribed` with code other than 0 -> `onError("... rejected ...")` (a server-side permission denial arrives as `unsubscribed` code 103, not `error`). The client never calls `publish`.
- **Reconnect and state model.** `createRealtime` passes only `getToken` to the SDK, so reconnect behaviour is the SDK default (`centrifuge` 5.8.0: `minReconnectDelay` 500 ms, `maxReconnectDelay` 20000 ms, `timeout` 5000 ms, `maxServerPingDelay` 10000 ms; read from `node_modules/centrifuge/build/index.js`). UI state: SDK `connecting` -> `connecting` (or `reconnecting` after the first connect), `connected` -> `live`, `disconnected` -> `offline`; labels `Connecting`, `Live`, `Reconnecting`, `Offline` (`CONNECTION_LABEL`). `onSubscribed` fires on the first subscribe and after every resubscribe; both UIs reload history from REST there to fill gaps.
- **Event model** (`types.ts`): `ChatEvent = MessageCreatedEvent | ConversationCreatedEvent`. `mergeMessages` (`messages.ts`) merges by id and sorts by `createdAt`, then `id`.
- **Widget** (`apps/chatwidget/src/chat-platform-widget.ts`): on connect it calls `POST /api/v1/demo/conversations`, loads history, then subscribes only to `chat:conversation:<id>` and calls `connect()`. Attributes `api-base-url` and `realtime-url`. Shows a notice when `realtimeDelivery` is `FAILED`.
- **Dashboard** (`apps/chatdashboardapp/src/app/agent/agent-inbox.tsx`): the server component `agent/page.tsx` reads `PUBLIC_API_BASE_URL` and `PUBLIC_CENTRIFUGO_WS_URL` and passes them to a client component. The client connects as `agentbob`, subscribes to `chat:inbox` (every event triggers `GET /api/v1/demo/conversations?actorId=agentbob`), selects the first conversation, and subscribes to `chat:conversation:<selectedId>`. All API calls are made by the browser, not by the Next server.

### 4.6 Client-side publication

Not enabled. The `chat` namespace has no client-publish option (`infra/centrifugo/config.json`), and `createRealtime` exposes no publish function. The test `tests/contract/platform-api.spec.ts` asserts that `sub.publish(...)` is rejected with code 103, that a token-less subscription to `chat:inbox` ends in `unsubscribed` code 103, and that an invalid connection token disconnects with code 3500 (the test exists; ADR-0004 records it as validated; **not re-run here**). Authorization of a subscription is only (a) the backend's decision to sign a subscription token (4.4) and (b) Centrifugo verifying the signature and the `channel` claim. Writes reach Centrifugo only from the backend, using the server API key.

### 4.7 CORS, origins and ports

- Backend CORS: `shared/WebConfig.java` maps `/api/**`; allowed origins come from `app.cors.allowed-origins` = `APP_CORS_ALLOWED_ORIGINS` (default and `.env.example` value `http://localhost:3000,http://localhost:5173`); methods `GET` and `POST` only; exposes `X-Trace-Id`; `allowedHeaders` and credentials are not set (Spring defaults).
- Centrifugo origin check: `client.allowed_origins` in `infra/centrifugo/config.json` (same two origins, edited by hand).
- Widget bundle: `apps/chatwidget/nginx.conf` adds `Access-Control-Allow-Origin: *` to the static files so the module script can be embedded on another origin; the bundle holds no secrets.
- So changing an origin means editing three places: `.env` (`APP_CORS_ALLOWED_ORIGINS`), `infra/centrifugo/config.json`, and, for links, `PUBLIC_WIDGET_DEMO_URL`. Browser-facing URLs must be `localhost` URLs, never Compose service names. Ports: section 3.3.

### 4.8 The two demo identities, and why they are not authentication

- `DemoActor` (`chat/DemoActor.java`) is an enum of exactly two values: `userone` (role `VISITOR`) and `agentbob` (role `AGENT`). The widget hardcodes `userone`; the dashboard hardcodes `agentbob`.
- The caller names itself: `actorId` is a query parameter (reads, token requests) or a body field (send). The server validates it only against the two names and derives the role from the name. There is no password, session, cookie, bearer token or signature: `cookie`, `Bearer`, `localStorage`, `sessionStorage` and `tenant` do not occur in the application source, and `authorization` occurs only in a Javadoc comment (`realtime/package-info.java`); not found.
- Consequence (observed from the code, stated as inference for the threat): any HTTP client that can reach port 8080 can pass `actorId=agentbob`, list the inbox, read any conversation's history, post as the agent, and mint connection and subscription tokens for `chat:inbox` or any conversation channel. `POST /api/v1/demo/conversations` needs no identity at all.
- The UIs and docs label it: "LOCAL POC - NOT REAL AUTHENTICATION" (README, `docs/features/local-chat-mvp.md`, UI banners, Javadoc). `docs/architecture.md` states that real authentication, tenancy-scoped channels and an outbox are required before any shared environment.
- There is no widget identity either: no site key, widget key, tenant id or visitor/session id exists in the widget, the API or the tokens (not found). Every browser acts as `userone`.

## 5. Current schema

### 5.1 Migrations (`database/flywayMigration/sql/`)

| Migration | Creates |
|---|---|
| `V1__create_platform_bootstrap.sql` | `platform_bootstrap(id BIGINT GENERATED ALWAYS AS IDENTITY PK, setup_key TEXT NOT NULL UNIQUE, message TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())` plus one seed row `local-poc` |
| `V2__create_chat_tables.sql` | `chat_conversations`, `chat_messages` and 3 non-PK indexes (below) |

`chat_conversations`: `id UUID PK DEFAULT uuidv7()`, `visitor_id VARCHAR(80) NOT NULL`, `assigned_agent_id VARCHAR(80) NOT NULL`, `status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','CLOSED'))`, `created_at` and `updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()`, `last_message_at TIMESTAMPTZ` (nullable).
`chat_messages`: `id UUID PK DEFAULT uuidv7()`, `conversation_id UUID NOT NULL REFERENCES chat_conversations(id)` (no `ON DELETE` action), `sender_id VARCHAR(80) NOT NULL`, `sender_role VARCHAR(20) NOT NULL CHECK (sender_role IN ('VISITOR','AGENT'))`, `body TEXT NOT NULL CHECK (length(btrim(body)) > 0 AND char_length(body) <= 4000)`, `created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()`.

Indexes (confirmed against the live database with `pg_indexes`): `ux_chat_conversations_open_visitor` UNIQUE on `(visitor_id) WHERE status = 'OPEN'` (get-or-create relies on `INSERT ... ON CONFLICT ... DO NOTHING`); `ix_chat_conversations_agent_activity` on `(assigned_agent_id, COALESCE(last_message_at, created_at) DESC)`; `ix_chat_messages_conversation_created` on `(conversation_id, created_at, id)`; plus the primary keys.

- No `tenant_id` anywhere; no foreign key from `visitor_id` or `assigned_agent_id` to any table (identities are enforced by the API, not the schema, "so real identities can replace them").
- `CLOSED` is allowed by the CHECK but **no code path writes it** (`grep CLOSED` over backend `src/main`: not found).
- ids and timestamps are produced by the database (`uuidv7()` needs PostgreSQL 18); `updated_at` is set by `ChatRepository.touchConversation` in the same transaction as the message insert.
- Transaction boundary: `ChatService.send` inserts the message and touches the conversation inside one `TransactionTemplate`; publication happens after commit.
- **Live state (read-only query at check time):** `platform.flyway_schema_history` has versions 1 and 2, both `success = t`; tables in schema `platform`: `chat_conversations`, `chat_messages`, `flyway_schema_history`, `platform_bootstrap`, all owned by `platform_migrator`; 1 conversation and 26 messages from earlier runs; server 18.6.

### 5.2 Roles and schema (`database/postgres-init/01-roles.sh`)

Runs **once**, on first initialisation of the `postgres-data` volume (`docker-entrypoint-initdb.d`); changing a password in `.env` later does not change the role (documented). It creates two login roles from environment variables, `POSTGRES_MIGRATOR_USER` (default `platform_migrator`) and `POSTGRES_APP_USER` (default `platform_app`), then: `REVOKE ALL ON DATABASE ... FROM PUBLIC`; `GRANT CONNECT` to both; `REVOKE CREATE ON SCHEMA public FROM PUBLIC`; `CREATE SCHEMA platform AUTHORIZATION <migrator>`; `GRANT USAGE ON SCHEMA platform TO <app>`; default privileges for objects the migrator creates in `platform`: `SELECT, INSERT, UPDATE, DELETE` on tables and `USAGE, SELECT` on sequences to the app role; `ALTER ROLE ... SET search_path = platform` for both. The superuser is `POSTGRES_ADMIN_USER` (`platform_admin`; live `pg_roles`: superuser true). Live check: `platform_app` and `platform_migrator` are not superusers and can log in.

- **Runtime role** `platform_app`: DML on `platform` objects (including `DELETE`, which no code path currently uses), no DDL; the backend connects as this role (`spring.datasource.username`). Hikari `initialization-fail-timeout: -1` lets the backend start with PostgreSQL down so `/api/v1/status` can report it. The backend has no Flyway runtime dependency (ADR-0003).
- **Migration role** `platform_migrator`: owns schema `platform`; used only by the `flyway-migration` container (`FLYWAY_SCHEMAS=platform`).

### 5.3 Test-time Flyway (`ChatIntegrationTests`)

`services/modulithplatformservice/src/test/java/com/byteflock/chatplatform/chat/ChatIntegrationTests.java` starts `postgres:18.6-alpine3.24` through Testcontainers (`@Testcontainers(disabledWithoutDocker = true)`; skipped without Docker) and, in `@BeforeAll`, runs `Flyway.configure().dataSource(url, user, password).schemas("platform").locations("filesystem:" + System.getProperty("flyway.sql.dir", ...)).load().migrate()` against the real `V1` and `V2`. Surefire passes `flyway.sql.dir` = `${project.basedir}/../../database/flywayMigration/sql`. Flyway is a test-scope dependency only (`flyway-core`, `flyway-database-postgresql`, version managed by Boot: 12.4.0 per tool-versions, while the runner image is 13.11.1). The container's own user runs both Flyway and the Spring datasource, so **the JUnit tests do not exercise the DML-only role or `01-roles.sh`**; that is covered only by running the full stack. `BootstrapIntegrationTests` uses the same pattern; `ChatServiceTests` is a Mockito unit test; `CentrifugoStub` is an in-process HTTP stub for the publish API.

## 6. Allocation engine

### 6.1 Location, modules and runtime

- Path: `D:\BYTEFLOCK\RESEARCH\Allocationservice-research\allocation-engine` (project doc: `HANDOVER.md`, `README.md`, `CLAUDE.md`; status per `HANDOVER.md`: all 8 phases done, never committed).
- Modules: `backend/core` (artifact `allocation-core`, pure Java 25, no framework imports; `CorePurityTest` enforces it), `backend/service` (artifact `allocation-service`, Spring Boot 4.1.2, Flyway, JDBC, springdoc; parent `com.byteflock.allocation:allocation-backend:0.1.0-SNAPSHOT`), `ui/` (React + TypeScript + Vite workbench, served by the service from `ui/dist`), `infra/compose.yaml` (PostgreSQL only), `scripts/` (`dev-up`, `test-all`, `load-test`, `java25-env`), `docs/`.
- Boot version: 4.1.2 (parent POM for version management). JDK: strictly 25 (`[25,26)`). Maven: wrapper, `[3.9,)`.
- Database: PostgreSQL via `infra/compose.yaml`: image `postgres:17-alpine` (platform uses 18.6), `container_name: allocation-postgres`, volume `allocation-pgdata`, database `allocation`, user `allocation`, password defaults to the same fake literal (`.env.example`: "Local development values only. Fake credentials"), host mapping `127.0.0.1:${ALLOCATION_DB_PORT:-5432}:5432`. There is no `.env` in the engine directory (only `.env.example`), so `application.yaml` defaults apply.
- Service: `server.port` = `ALLOCATION_PORT` (default **8080**), bound to `127.0.0.1` (`ALLOCATION_BIND_ADDRESS`); datasource `ALLOCATION_DB_URL` (default `jdbc:postgresql://localhost:5432/allocation`), `ALLOCATION_DB_USER`, `ALLOCATION_DB_PASSWORD`. Flyway runs inside the service at start (`spring.flyway.enabled: true`, migrations `V1`-`V5` in `backend/service/src/main/resources/db/migration`), unlike the platform. Demo data is seeded on start (`ALLOCATION_SEED_DEMO`, default true), a scheduled sweep runs every 5 s (`ALLOCATION_DISPATCHER_DELAY`, batch 20), feature level defaults to `basic` (`ALLOCATION_FEATURE_LEVEL`; `workbench` enables everything). Other variables in `.env.example`: `ALLOCATION_MAX_REQUEST_BYTES`, `ALLOCATION_DISPATCHER_ENABLED`, `ALLOCATION_CORS_ORIGINS` (default `http://localhost:5173`).
- Start command (from `allocation-engine/backend`, with JDK 25): `docker compose -f ../infra/compose.yaml up -d`, then `JAVA_HOME="C:/Program Files/Java/jdk-25.0.3" ./mvnw -q install -DskipTests && JAVA_HOME="C:/Program Files/Java/jdk-25.0.3" ./mvnw -q -pl service spring-boot:run` (never `-am` with `spring-boot:run`: the parent POM has no main class). `scripts/dev-up.ps1|sh` does the same plus the UI build.
- Other commands (documented in `HANDOVER.md`/`CLAUDE.md`, **not re-run**): `./mvnw clean verify` (216 core + 102 service tests per the doc; service tests need Docker/Testcontainers with `postgres:17-alpine`), `./mvnw -pl core verify` (no Docker), `bash scripts/load-test.sh <label>`, UI `pnpm test | build | e2e`.
- Health and docs endpoints: `GET /api/v1/health` (needs no tenant), `/actuator/health` (+ probes), `/actuator/metrics`, `/swagger-ui.html`, `/v3/api-docs.yaml`.
- **Dockerfile: none** (`find allocation-engine -iname 'Dockerfile*'` found nothing). Only PostgreSQL is containerised. Because the service binds to `127.0.0.1` by default, a future container would need `ALLOCATION_BIND_ADDRESS=0.0.0.0`.
- **Port collisions with the platform:** engine service 8080 (platform backend 8080), engine PostgreSQL host port 5432 (platform PostgreSQL 5432), engine UI dev server 5173 (platform widget 5173; only when the engine's own `pnpm dev` runs), engine Playwright e2e starts its jar on 8081 (the planned allocation port, only while that e2e runs).
- **Planned (decisions from the plan overview, not existing):** run the allocation service on host port **8081** with its own PostgreSQL on host port **5433**, and add a Dockerfile later. Inference from the files: `infra/compose.yaml` takes the DB host port from `ALLOCATION_DB_PORT`, but the service reads `ALLOCATION_DB_URL`, so moving the database to 5433 needs `ALLOCATION_DB_PORT=5433`, `ALLOCATION_DB_URL=jdbc:postgresql://localhost:5433/allocation` and `ALLOCATION_PORT=8081` set together.
- Local-dev auth only: `LocalDevAuthInterceptor` reads `X-Tenant-Id` (default `demo-tenant`; the tenant must exist, else 404 `TENANT_NOT_FOUND`), `X-Role` (`viewer` default, `operator`, `admin`) and `X-Correlation-Id`. `Idempotency-Key` (max 128 characters) is required on `dispatch`, `claim` and `reassign`.

### 6.2 Summary against spec 2.2

The full analysis is the separate deliverable `docs/research/allocation-service-integration.md` (a later task; **not yet written**). This table answers each spec 2.2 bullet at inventory depth from files read in this task; behavioural claims about concurrency and tests are **documented, not re-run**.

| Spec 2.2 bullet | Finding | Source |
|---|---|---|
| Domain objects and status model | Core records: `AgentSnapshot`, `QueueMembership`, `ConversationSnapshot`, `AllocationSnapshot`, `PolicyDefinition`, `AllocationRequest`, `AllocationDecision`, `CandidateTrace`, `FilterResult`, `CapacityView`, `FallbackAction`. Tables: `tenant`, `queue`, `agent`, `agent_queue_membership`, `conversation`, `assignment`, `allocation_policy`, `policy_version`, `allocation_decision`, `round_robin_cursor`, `idempotency_record`, `simulation_scenario`, `simulation_run`, `regression_case`, `tenant_feature`. Conversation status `QUEUED, ASSIGNED, ACCEPTED, ACTIVE, RESOLVED, CANCELLED`; assignment status `ASSIGNED, ACCEPTED, ACTIVE, COMPLETED, REJECTED, TIMED_OUT, TRANSFERRED, REASSIGNED, CANCELLED`; agent status `ONLINE, AWAY, OFFLINE`; policy state `DRAFT, PUBLISHED, DISABLED`. Ids are `text` and tenant-scoped (composite PK `(tenant_id, id)`), `assignment.id` and decision ids are UUID. | `allocation-engine/docs/architecture/domain-model.md`, `backend/service/src/main/resources/db/migration/V1__schema.sql` |
| Public API and DTOs | REST under `/api/v1`: catalogue (`/tenants`, `/queues`, `/agents`, `/agents/{id}/presence`), `/conversations` (create, get, `accept`, `activate`, `resolve`, `requeue`, `reassign`, delete, `/assignments`, `/decisions`), `/policies` (draft, validate, publish, versions, rollback, enable, disable), `/allocations` (`preview`, `dispatch`, `claim`, `sweep`, `decisions`), scenarios, simulations, regression cases, `/features`. Request records: `AllocationRequestBody(queueId, conversationId, claimingAgentId, policyId, policyVersion)`, `ClaimRequest(conversationId, agentId)`, `SweepRequest(queueId, maxAssignments)`, `PresenceRequest(status, heartbeatAt)`, `RequeueRequest(reason, excludePreviousAgent, targetQueueId)`, `CreateConversationRequest`. Response: `AllocationDecision` (outcome, selected conversation/agent, policy id/version/hash, candidate trace, `explanation`). Errors `{status, code, message, issues[], correlationId}`. Exported contract `docs/api/openapi.yaml`. Not found: a Java client library, outbound events or webhooks. | `backend/service/src/main/java/com/byteflock/allocation/service/api/*Controller.java`, `docs/api/README.md` |
| Selection policies and eligibility rules | Selection `fifo`, `oldest-created`, `priority-then-fifo`, `priority-aging`. Mandatory filters `tenant`, `agent-enabled`, `queue-membership`, `availability`, `capacity`, `excluded-agents`; optional `required-skills`, `language`. Ranking `round-robin`, `least-loaded`, `least-recently-assigned`, `fewest-total-assigned`. Capacity `count-based`. Fallback `keep-queued`, `move-to-queue`. Policies are typed data with immutable published versions; one evaluator serves dispatch, preview and simulation. Many of these are gated by 18 feature flags in four levels. | `HANDOVER.md` section 5, `docs/architecture/domain-model.md` |
| Agent availability and presence: model and how it is obtained | Columns on `agent`: `status` (`ONLINE`/`AWAY`/`OFFLINE`) and `last_heartbeat_at`; the `availability` filter fails an agent that is offline, away (unless allowed), without a heartbeat (`PRESENCE_UNKNOWN`) or with a heartbeat older than the policy's `heartbeatTtlSeconds` (`PRESENCE_STALE`). The engine does not observe presence: callers push it with `POST /api/v1/agents/{id}/presence` (role `operator`); the demo seeder refreshes demo-tenant agents. | `V1__schema.sql`, `CatalogController.java`, `strategy/eligibility/AvailabilityFilter.java`, `CLAUDE.md` |
| Capacity reservation and concurrency guarantees | Capacity is never stored: load is counted from `assignment` rows in `ASSIGNED, ACCEPTED, ACTIVE`; `null` = unlimited, `0` = accepts nothing. Guards: `FOR UPDATE SKIP LOCKED` on the conversation, `FOR UPDATE` on the cursor row (round-robin only) and on the winning agent with a recount and re-verification of eligibility, status-preconditioned updates, and a partial unique index `assignment_one_active_per_conversation` (one active assignment per conversation). Retries up to 3 times with jitter, then a non-persisted `CONFLICT_RETRYABLE`. | `docs/architecture/concurrency-and-consistency.md`, `V1__schema.sql` |
| Database schema, transaction boundaries, idempotency | Own PostgreSQL database and schema (section 6.1); 14 tables in V1, V2-V5 add run limits, a decision index and feature tables. One `Dispatcher` transaction (READ COMMITTED) covers conversation lock, snapshot, evaluation, agent lock, writes, idempotency record and decision audit; no notification inside it. Idempotency: `idempotency_record(tenant_id, key, request_hash, response)`; decision id derived from `tenantId:key`; same key with a different body is 409 `IDEMPOTENCY_KEY_REUSED`. The engine's rows are **not** in the platform's database or transaction. | `concurrency-and-consistency.md`, `V1__schema.sql`, `HANDOVER.md` section 6 |
| How assignments are recorded, changed, released, retried | A decision plus an `assignment` row (with `decision_id`) per allocation; `accept`, `activate`, `resolve` advance it; reject/timeout/transfer/requeue return the conversation to `QUEUED`, add the previous agent to `excluded_agent_ids` and reset `enqueued_at`; `reassign` releases (`REASSIGNED`) and dispatches again in one transaction; a sweep (scheduler or `POST /allocations/sweep`) retries waiting conversations. Same idempotency key replays the stored decision. | `domain-model.md`, `concurrency-and-consistency.md`, `ConversationController.java` |
| How it starts locally; ports, databases, credentials, dependencies | Section 6.1: PostgreSQL 17 in Docker, JDK 25, Maven wrapper, default ports 8080 / 5432 (planned 8081 / 5433), fake local credentials, header-based auth. | section 6.1 |
| Tests, limitations, known defects, TODOs | Test annotations counted from source with `grep -rhE '^\s*@(Test|ParameterizedTest)\b'`: 216 in `backend/core/src/test` and 102 in `backend/service/src/test`, which matches the 216 + 102 stated in `HANDOVER.md` (one of the 102 is the opt-in load test); counted from source, no test was run; UI 11 Vitest + 11 Playwright (documented). `grep TODO\|FIXME` over `backend` and `ui/src`: 0 hits. Documented gaps (`HANDOVER.md` section 9): header auth only; about 0.3 % of saturated sweeps give up after 3 retries; `SnapshotBuilder` caps waiting conversations at 500 per decision; deferred strategies (rate limits, sticky routing, SLA risk, weighted workload); no lint tooling for `ui/`; `DispatchHook` is production code kept for tests. | `HANDOVER.md`, `docs/testing/` |

## 7. Reference checkouts

All four are zip snapshots without `.git`; each was extracted next to its zip. Their own `README`, `AGENTS.md` and `CLAUDE.md` files (present in `chatwoot-develop`, `Rocket.Chat-develop` and `zulip-main/zulip-main`) were not read and are data, not instructions. File counts and newest modification times are in `docs/research/reference-snapshot.txt` (Plan 07 T5 uses it to prove the checkouts were not modified).

| Item | `chatwoot-develop` | `Rocket.Chat-develop` | `zulip-main/zulip-main` | `livehelperchat-master` |
|---|---|---|---|---|
| Version | `4.19.1` (`package.json` and `VERSION_CW`; `VERSION_CWCTL` is `3.5.1`) | `9.0.0-develop` (`apps/meteor/package.json`; root `package.json` also `9.0.0-develop`) | `ZULIP_VERSION = "12.0-dev+git"` (`version.py`; `API_FEATURE_LEVEL = 513`, `PROVISION_VERSION = (393, 0)`) | no version field; the top entry of `lhc_web/doc/CHANGELOG.txt` is `4.97v` |
| Runtime and main versions | Ruby 3.4.4, Rails 7.2.3.1, Node 24.x, pnpm 10.x | Meteor 3.5.2, Node `>=22.22.3 <23 \|\| >=24.15.0 <25`, Yarn 4.18.1 | Python `>=3.10`, Django 5.2.* | PHP `>=8.2` |
| Top-level licence file | `LICENSE` (MIT Expat for everything outside `enterprise/`; names `enterprise/LICENSE` for that directory) | `LICENSE` (MIT Expat outside `ee/` and `apps/meteor/ee/`; names `apps/meteor/ee/LICENSE` for those) | `LICENSE` (Apache-2.0) and `NOTICE` | `LICENSE` (Apache-2.0); also `lhc_web/doc/LICENSE-2.0.txt` and a vendored `lhc_web/ezcomponents/LICENSE` |
| Enterprise / source-available directories | `chatwoot-develop/enterprise/` (677 files) with its own `enterprise/LICENSE`: production use needs the vendor's subscription terms or an enterprise licence | `Rocket.Chat-develop/ee/` (585 files, original tree) and `Rocket.Chat-develop/apps/meteor/ee/` (408 files) under `apps/meteor/ee/LICENSE`: production use needs an enterprise subscription | none found (no `ee` or `enterprise` directory within depth 3). `corporate/` and `zilencer/` exist; no separate licence file found, so the root Apache-2.0 presumably covers them (**inference**) | none found (same search) |
| Build, test, lint entry points (from manifests; not run) | `package.json`: `test` (vitest), `eslint`, `dev`/`start:dev` (overmind/foreman); Rails via `bin/rails`, `bin/rake`; `Procfile.dev` | root `package.json`: `build`, `dev`, `lint`, `testunit` (turbo, Yarn) | `tools/test-all`, `tools/test-backend`, `tools/lint`, `tools/run-dev`, `tools/provision` | `lhc_web/package.json`: `build` (gulp), `webpack`; `composer.json` |
| Snapshot (file count / newest mtime UTC) | 9,793 / 2026-10-23T16:07:54Z | 27,070 whole tree (13,535 original + 13,535 duplicate) / 2026-10-27T00:31:14Z; original tree 13,535 / 2026-10-26T07:55:14Z | 9,022 / 2026-10-27T00:30:54Z | 6,320 / 2026-10-26T07:51:13Z |
| Zip (size bytes, SHA-256) | `chatwoot-develop.zip`, 113,880,505, `12948277649b0f636db9bd6e55f83dfbf8645f1bf3ac16ed29cc8238455e8c38` | `Rocket.Chat-develop.zip`, 101,386,771, `e0e2418c23d07e70507a846ba5622e7dade2bb8d32d03ac436b0b6c39f438eb5` | `zulip-main.zip`, 63,564,823, `6baeda4e4723a93d2e033931371614bdea9ad02a8c8535207ba13adab29b7892` | `livehelperchat-master.zip`, 41,873,033, `bae07c97287b14ba751564b5ca21ca0e1b5b01b66dc0b1721d2fb9f29c083be5` |
| Zip file entries vs extracted files | 9,793 = 9,793 | 13,535 entries vs 13,535 original (plus a duplicate subtree, section 9) | 9,029 entries vs 9,022 extracted (7 missing, section 9) | 6,320 = 6,320 |
| Git metadata | zip snapshot, no git metadata | zip snapshot, no git metadata | zip snapshot, no git metadata | zip snapshot, no git metadata |

- Zip entry counts were read with .NET `System.IO.Compression.ZipFile.OpenRead` (read-only).
- Enterprise code is not to be copied or relied on in the platform; it is a source of ideas only (licence handling belongs to the research plans, not this inventory).
- The extracted trees have not been modified by this task (only read with `find`, `ls`, `grep`, `head`, `sed`).

## 8. Integration boundaries and gaps

### 8.1 Integration boundaries

| # | Boundary | Protocol / address | Direction and trigger | Authentication today | Failure behaviour today |
|---|---|---|---|---|---|
| 1 | Browser (widget) <-> platform | HTTP/JSON, `http://localhost:8080/api/v1/demo/*`, CORS allow-list | browser initiates (`fetch`, 8 s timeout); on load and on send | none: client-named `actorId` | `ApiError` with status 0 ("Cannot reach the API") or the problem-details text; UI shows an error banner |
| 2 | Browser (dashboard) <-> platform | same API | browser initiates; the Next server component only injects `PUBLIC_*` config | none: hardcoded `agentbob` | same |
| 3 | Browser <-> Centrifugo | WebSocket `ws://localhost:8000/connection/websocket`; connection JWT plus per-channel subscription JWT; origin allow-list | browser connects and subscribes; server pushes `message.created`, `conversation.created` | HS256 JWTs signed by the backend (4.4) | SDK reconnect with 0.5 to 20 s back-off; badge `Reconnecting`/`Offline`; history reloaded from REST after resubscribe; terminal denial arrives as `unsubscribed` 103 |
| 4 | Platform <-> Centrifugo | HTTP `POST {CENTRIFUGO_URL}/api/publish` with `X-API-Key`; `GET /health` for `/api/v1/status`; shared HMAC secrets (no network call) | backend pushes after commit | server API key | publish failure -> `realtimeDelivery: FAILED`, message kept, no retry (at-most-once); status check shows `DEGRADED` |
| 5 | Platform <-> PostgreSQL | JDBC `jdbc:postgresql://<host>:5432/chatplatform?currentSchema=platform` as `platform_app`; Flyway runner container as `platform_migrator` | request-time reads/writes; migration at deploy | database roles (DML-only app, DDL-only migrator) | backend starts without DB; `DataAccessException` -> 503 problem+json |
| 6 | Platform <-> allocation service | **Does not exist.** Not found: `grep -rni allocation` over platform source, config, SQL, JSON and YAML (excluding `docs/superpowers` and `.superpowers`) returned nothing. **Planned:** HTTP/JSON to the engine on host port 8081, engine PostgreSQL on 5433 | (planned) platform calls dispatch/claim/reassign and pushes presence | engine: local-dev headers `X-Tenant-Id`, `X-Role` only | (planned) separate databases, so no distributed transaction: partial failure and retry must be handled with the engine's idempotency keys |
| 7 | Platform <-> observability | OTLP/HTTP to `OTEL_EXPORTER_OTLP_ENDPOINT` (`/v1/traces`, `/v1/logs`); Prometheus scrapes `/actuator/prometheus` (Mode A: `host.docker.internal:8080`; Mode B: `modulithplatformservice:8080`) | push (OTLP) and pull (scrape) | none; ports bound to 127.0.0.1 | export failures are not fatal (inference); `X-Trace-Id` on every response |
| 8 | Test harness <-> stack | Playwright to 3000, 5173, 8080 and Centrifugo `ws://localhost:8000`; direct read-only SQL as `platform_app` from `.env` (`tests/support/db.ts`) | test run | `.env` credentials (read, never logged) | tests fail; servers on 3000/5173 reused if already running |

### 8.2 Gaps the spec expects the plans to close

From the brief; each has the evidence in this workspace.

1. **No tenancy.** "tenant" does not occur in the apps, `libs/chat-client` or backend `src/main` (not found); no `tenant_id` in V1/V2; channels and tokens carry no tenant; one global inbox.
2. **No outbox.** `ChatService.send` publishes directly after commit; at-most-once; no retry; no event sequence.
3. **No assignment.** `assigned_agent_id` is the constant `agentbob` set when the demo conversation is created (`ChatService.ensureDemoConversation`); no queue, no allocation call, no reassignment.
4. **No lifecycle.** `status` is `OPEN` or `CLOSED` but nothing writes `CLOSED`; no close, resolve or reopen endpoint; one OPEN conversation per visitor.
5. **No read cursors.** No read-state table, endpoint or event; no unread counts.
6. **No typing or presence.** No typing events; the Centrifugo namespace has no presence or join/leave; agent availability is not modelled.
7. **No retention.** No purge, archive or TTL job; messages are never deleted.
8. **No load harness.** No k6, Gatling, Artillery, JMeter or Locust file among the 229 tracked files at `57ce803` (the allocation engine has its own `scripts/load-test.sh`; that tests the engine only).

Additional gaps observed (not in the brief's list): real authentication and authorization; pagination of inbox and history; idempotent send (no client message id, so a retried `POST` creates a second message); attachments; rate limiting and abuse protection; Centrifugo high availability, history/recovery, TLS and key rotation; origin lists maintained in three places; no per-visitor identity or session (every browser is `userone`); JUnit runs the app as the database superuser (5.3).

## 9. Findings that differ from the brief or from what the project docs say

1. **Ignore rules.** Expected: a `D:\BYTEFLOCK\.gitignore` excluding the zips and checkouts. Actual: no such file. `RESEARCH/Allocationservice-research/.gitignore` excludes `*.zip`, `Rocket.Chat-develop/`, `chatwoot-develop/`, `livehelperchat-master/` but **not** `zulip-main/`, so about 9,020 zulip paths show as untracked in `git status` at `D:\BYTEFLOCK`. A careless `git add -A` there would stage them. Not changed (outside `docs/research`).
2. **Rocket.Chat duplicate.** `Rocket.Chat-develop/Rocket.Chat-develop/` is a second copy of the whole tree (identical relative paths and file sizes; created 2026-10-27 06:00-06:01 local, the original tree's newest file is 2026-10-26 13:25 local, 07:55:14Z, as in `reference-snapshot.txt`). The zip's root folder is `Rocket.Chat-develop`, so this looks like the zip extracted a second time into the existing folder (**inference**). It doubles the whole-tree count to 27,070. Searches in later plans should use the original tree (13,535 files) and ignore the nested copy.
3. **Zulip extraction.** The zip lists 9,029 files; 9,022 are on disk. The 7 missing are test fixtures under `zerver/tests/fixtures/mattermost_*_fixtures/.../data/...` with very long paths (some with spaces); probably skipped by the Windows extractor's path-length limit (**inference**). Irrelevant to the research, but a "file count equals zip" check will fail by 7.
4. **Zulip mtimes.** Zulip's files carry the extraction time (2026-10-27 06:00), unlike chatwoot and livehelperchat which keep the archive times.
5. **Engine test count: no discrepancy.** `HANDOVER.md` says 216 core + 102 service tests; counting `@Test` / `@ParameterizedTest` annotations with a word boundary gives 216 + 102 (a first count without the boundary gave 106 because it also matched four `@TestPropertySource` lines). Counted from source; no test was run.
6. **Platform licence.** `package.json` says MIT, the OpenAPI document says Proprietary, and there is no LICENSE file.
7. **JDK pinning.** The platform accepts JDK 25 or newer; the engine only JDK 25. The default machine JDK is 27, so engine commands need an explicit `JAVA_HOME`.
8. **Port collisions** between the engine's defaults and the platform: section 6.1. The planned 8081 and 5433 were free at check time, but the engine's own e2e uses 8081.
9. **Docker.** The brief says to start Docker Desktop if `docker info` fails; it did not fail, and the infrastructure stack was already up (started by the coordinating agent with `./startlocalsetup.sh --infra`, not by this task; this task did not start, stop or modify any container).

## 10. Spec 2.1 to 2.3 checklist

Every item below is answered in this file; the section named is where. Items marked "deferred" are separate deliverables outside this task.

**2.1 Discover directories and projects**

- [x] Identify Git roots and application roots; find checkouts by repository URL: section 2.1 (roots), and the finding that URL lookup is impossible without `.git`.
- [x] Actual directory path: section 2 (each repo), section 3.1 (each application).
- [x] `git remote -v` output: 2.2 (none), 2.3 (`origin`), 2.4 and 2.5 (none).
- [x] Current branch and `git rev-parse HEAD`: 2.2 (`main`, no commits), 2.3 (`feat/conversation-platform`, `57ce803f...`), 2.4 and 2.5 (none).
- [x] `git status --short` before changes: 2.2, 2.3 (clean), 2.4 (all untracked), 2.5 (no git).
- [x] Licence file and enterprise / paid-licence directories: 2.3, 2.4, section 7.
- [x] Main language/runtime and current dependency versions: 2.3, 2.4, 3.1, section 7.
- [x] Build, test, lint and local-run commands: 2.3, 3.2, 6.1, section 7.
- [x] Local modifications that must not be overwritten: 2.2, 2.3, 2.4, 2.5.
- [x] Reference working trees unchanged; research notes stored outside the references: section 7 (last bullet), header (only `docs/research/` was written).

**2.2 Discover the existing allocation service**

- [x] Domain objects and status model: 6.2.
- [x] Public API/interface and request/response DTOs: 6.2.
- [x] Selection policies and eligibility rules: 6.2.
- [x] Agent availability/presence model and how it is obtained: 6.2.
- [x] Capacity reservation and concurrency guarantees: 6.2.
- [x] Database schema, transaction boundaries and idempotency: 6.2.
- [x] How assignments are recorded, changed, released and retried: 6.2.
- [x] How it starts locally; ports, databases, credentials, dependencies: 6.1.
- [x] Tests, limitations, known defects and TODOs: 6.2.
- [ ] Deferred: `docs/research/allocation-service-integration.md` (full capability document) and the typed `AllocationService` / `AllocationClient` boundary. This inventory states that the engine's decisions and assignments are not part of the platform's database or transaction (6.2, row "Database schema").

**2.3 Discover the existing widget and dashboard**

- [x] Framework/build tool and dev commands: 3.1, 3.2.
- [x] Widget identity, visitor/session identity and agent authentication today: 4.8.
- [x] How Centrifugo connection tokens and channel subscriptions are issued: 4.4.
- [x] Existing channel names, publish formats, reconnect handlers and UI event models: 4.2, 4.5.
- [x] Whether client-side publication is enabled and how it is authorized: 4.6.
- [x] CORS, allowed origins and ports: 4.7, 3.3.

**Required Phase 0 artifact:** identifies every app (3.1), repo (2), runtime (1, 3.1, 6.1, 7), start command (3.2, 6.1), port (3.3, 6.1) and relevant integration boundary (8.1).
