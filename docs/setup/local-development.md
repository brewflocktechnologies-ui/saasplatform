# Local development

## 1. Prerequisites

| Tool | Version | Check |
|---|---|---|
| Docker Desktop | running, Compose v2+ | `docker info`, `docker compose version` |
| Node.js | 24 LTS | `node --version` |
| pnpm | 10.11.0 via Corepack | `corepack enable`, `pnpm --version` |
| JDK | 25 (27 also works) | `java -version`, `$env:JAVA_HOME` |
| Git | any recent | `git --version` |

If several JDKs are installed, point `JAVA_HOME` at JDK 25 for the current shell only:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.3'
```

## 2. First-time setup

```powershell
corepack enable
powershell -ExecutionPolicy Bypass -File scripts/dev-setup.ps1
```

The script checks prerequisites, creates `.env` from `.env.example` **only if it does not exist** (random hex
secrets for PostgreSQL roles, Centrifugo and Grafana; values are never printed). If `.env` exists it never changes
existing values; it only **appends keys added to `.env.example` later** (e.g. the chat MVP's
`CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY`). Rerun it after pulling. Then it runs
`pnpm install --frozen-lockfile` and installs Playwright's Chromium. It is safe to rerun.

## 3. Ports (authoritative map: `.env.example`)

| Service | Host URL | Container |
|---|---|---|
| Dashboard | http://localhost:3000, agent inbox `/agent` | `chatdashboardapp:3000` |
| Widget | http://localhost:5173, visitor chat `/demo` (dev, preview and container mode) | `chatwidget:8080` |
| Backend | http://localhost:8080 | `modulithplatformservice:8080` |
| PostgreSQL | 127.0.0.1:5432 | `postgres:5432` |
| Centrifugo | http://localhost:8000 (WebSocket `/connection/websocket`) | `centrifugo:8000` |
| Prometheus | http://localhost:9090 | `prometheus:9090` |
| Grafana | http://localhost:3001 | `grafana:3000` |
| Loki | http://localhost:3100 | `loki:3100` |
| Tempo | http://localhost:3200 | `tempo:3200` (OTLP 4317/4318 internal only) |
| OTel Collector | OTLP gRPC 4317, HTTP 4318, health 13133 | `otel-collector` |

Changing a `*_HOST_PORT` in `.env` remaps the container port. If you move the dashboard (3000) or widget (5173),
also update `PUBLIC_WIDGET_DEMO_URL`, `APP_CORS_ALLOWED_ORIGINS` and `infra/centrifugo/config.json`
`allowed_origins`. Browser-facing URLs must be `localhost` URLs, never Compose service names.

## 4. Start / stop

```powershell
docker compose up -d --wait        # infra; Flyway runs once and exits 0
docker compose ps -a               # flyway-migration shows "Exited (0)"
docker compose stop                # stop, keep containers and data
docker compose down                # remove containers, KEEP volumes (data survives)
```

Never use `docker compose down -v` as routine cleanup — it deletes the database and telemetry volumes. See the
dev-only reset in [runbooks/local-stack.md](../runbooks/local-stack.md).

### Mode A — host hot reload

Infrastructure in Docker, apps on the host:

```powershell
pnpm dev:backend     # nx run modulithplatformservice:dev  -> mvnw spring-boot:run, profile "local"
pnpm dev:dashboard   # nx run chatdashboardapp:dev         -> next dev on :3000
pnpm dev:widget      # nx run chatwidget:dev               -> vite on :5173
```

`pnpm dev` runs all three in parallel in one terminal; separate terminals give clearer logs. The backend reads the
repo-root `.env` (`spring.config.import=optional:file:../../.env[.properties]`), so no extra setup is needed. It
sends traces/logs to `http://localhost:4318`, and Prometheus scrapes it through `host.docker.internal:8080`.

### Mode B — full container POC

```powershell
docker compose -f docker-compose.yml -f docker-compose.apps.yml config -q
docker compose -f docker-compose.yml -f docker-compose.apps.yml up --build -d --wait   # = pnpm stack:up
docker compose -f docker-compose.yml -f docker-compose.apps.yml ps
docker compose -f docker-compose.yml -f docker-compose.apps.yml down                   # = pnpm stack:down
```

Order is enforced with health conditions: postgres healthy → flyway-migration completed successfully → backend
healthy (`/actuator/health/readiness`) → dashboard and widget. Mode B uses the same host ports as Mode A; stop the
host dev servers first.

### Chat MVP (two browsers)

Browser A → http://localhost:5173/demo (visitor `userone`), Browser B → http://localhost:3000/agent (agent
`agentbob`). Works in both modes. Details, API, events and limitations:
[docs/features/local-chat-mvp.md](../features/local-chat-mvp.md). **LOCAL POC — NOT REAL AUTHENTICATION.**

## 5. Credentials policy

- All secrets live only in the git-ignored `.env`, generated per machine. `.env.example` holds placeholders.
- PostgreSQL roles (created once by `database/postgres-init/01-roles.sh` when the volume is first initialised):
  `platform_admin` (superuser, container only), `platform_migrator` (owns schema `platform`, used by Flyway),
  `platform_app` (DML only, used by the backend; cannot create/alter/drop).
- Connect with psql inside the container (no password typed or printed):
  `docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'`
- Grafana: user `admin`, password = `GRAFANA_ADMIN_PASSWORD` in `.env`.
- Centrifugo admin UI is disabled. Three Centrifugo secrets (connection-token HMAC, subscription-token HMAC, HTTP
  API key) live only in `.env` and are passed to Centrifugo and the backend — never to browser config
  (`PUBLIC_*` / `VITE_*`). Browsers receive only short-lived JWTs from `/api/v1/demo/realtime/*`.

## 6. Tests and checks

```powershell
pnpm lint            # ESLint incl. @nx/enforce-module-boundaries
pnpm typecheck
pnpm test            # Vitest (apps/libs) + Maven tests (incl. Testcontainers integration test; needs Docker)
pnpm build           # next build, vite build, mvnw package
pnpm contracts:validate
pnpm db:info; pnpm db:validate; pnpm db:migrate
pnpm e2e             # requires infra + backend; reuses running frontends or starts production builds
pnpm verify          # full gate; add -SkipRuntime via: powershell -File scripts/verify-local.ps1 -SkipRuntime
```

A single project: `pnpm nx test chatwidget`, `pnpm nx run modulithplatformservice:test`.

## 7. Logs, containers, health

```powershell
docker compose logs -f modulithplatformservice    # Mode B (JSON/ECS logs with trace ids)
docker compose logs -f postgres centrifugo otel-collector
docker compose ps -a
docker inspect --format '{{json .State.Health}}' chat-platform-postgres-1
curl.exe http://localhost:8080/actuator/health
curl.exe http://localhost:8080/api/v1/status
```

Every API response carries `X-Trace-Id`; search it in Tempo or Loki.

## 8. Observability

1. Open http://localhost:3001 → log in → **Dashboards → Chat Platform → Chat Platform - Local Overview**
   (scrape target state, request rate, latency, heap, backend logs).
2. Datasources (provisioned, read-only): **Connections → Data sources** → Prometheus / Tempo / Loki → *Save & test*.
3. Useful queries:
   - Prometheus: `max(up{job="modulithplatformservice"})`,
     `sum by (uri) (rate(http_server_requests_seconds_count{application="modulithplatformservice"}[1m]))`
   - Loki: `{service_name="modulithplatformservice"}`, `{service_name="modulithplatformservice"} | trace_id="<id>"`
   - Tempo: Explore → Tempo → TraceQL `{ resource.service.name = "modulithplatformservice" }` or paste a trace id.
4. Pipeline: backend → OTLP HTTP → Collector → Tempo (traces, gRPC) and Loki (logs, `/otlp`). Metrics are scraped
   by Prometheus from `/actuator/prometheus` (only exposed with the `local` profile) and from the Collector (`:8888`).
   Each mode scrapes exactly one backend target: Mode A uses `infra/observability/prometheus.yml`
   (`host.docker.internal:8080`); Mode B (`docker-compose.apps.yml`) mounts `prometheus.containers.yml`
   (`modulithplatformservice:8080`) instead, so series are never double-counted.
5. Chat: panels *Chat messages persisted*, *Centrifugo publications by outcome*, *Chat logs*. Recipe and queries:
   [docs/features/local-chat-mvp.md](../features/local-chat-mvp.md#observability).

## 9. Troubleshooting

See [troubleshooting.md](troubleshooting.md).

## 10. First feature

Copy `specs/features/_template/feature.spec.md` to `specs/features/<feature-name>/feature.spec.md`, fill it in
(acceptance criteria, contract and migration impact), then ask the agent to implement that spec. Process:
[specs/features/README.md](../../specs/features/README.md).
