# Runbook: local stack

## Start

```powershell
docker compose up -d --wait                       # infra + one-shot Flyway
pnpm stack:up                                     # or: full container mode (infra + app images)
```

`--wait` returns non-zero if any service fails its health check or Flyway exits non-zero.

## Inspect

```powershell
docker compose ps -a
docker compose logs --tail=100 <service>          # postgres, flyway-migration, centrifugo, otel-collector, ...
docker compose -f docker-compose.yml -f docker-compose.apps.yml logs -f modulithplatformservice
pnpm db:info                                      # migration state
pnpm verify                                       # full health + telemetry gate
```

Health endpoints: backend `/actuator/health`, Centrifugo `/health`, Prometheus `/-/ready`, Grafana `/api/health`,
Loki `/ready`, Tempo `/ready`, Collector `http://localhost:13133/`.

## Stop (non-destructive)

```powershell
docker compose stop                                               # pause everything
docker compose down                                               # remove infra containers, keep volumes
docker compose -f docker-compose.yml -f docker-compose.apps.yml down   # full stack, keep volumes
```

Data (PostgreSQL, Prometheus, Tempo, Loki, Grafana) lives in named volumes `chat-platform_*` and survives `down`.

## Recover

| Problem | Action |
|---|---|
| A container keeps restarting | `docker compose logs <service>`; fix config; `docker compose up -d <service>` |
| Flyway failed | read `docker compose logs flyway-migration`; fix by adding a new migration; `pnpm db:migrate` |
| Backend unhealthy in Mode B | `docker compose ... logs modulithplatformservice`; usually DB credentials or Flyway not completed |
| Changed app code, container stale | `pnpm stack:up` (rebuilds) |

## ⚠️ DEV-ONLY data reset — DESTROYS ALL LOCAL DATA

Deletes the database (all rows, roles and migrations) and all telemetry. Never run against a shared environment.
Use only when the local DB is unrecoverable (e.g. passwords in `.env` no longer match the initialised roles).

```powershell
docker compose -f docker-compose.yml -f docker-compose.apps.yml down
docker volume rm chat-platform_postgres-data            # database only
# everything (also metrics, traces, logs, Grafana state):
# docker compose -f docker-compose.yml -f docker-compose.apps.yml down -v
docker compose up -d --wait                               # re-initialises roles and re-applies migrations
```
