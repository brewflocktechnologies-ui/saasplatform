# Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `bind: address already in use` / `Port 5173 is already in use` | Another process or the other run mode owns the port | `Get-NetTCPConnection -State Listen -LocalPort 3000,5173,8080` → stop that process, or change `*_HOST_PORT` in `.env` (see local-development §3 for what else to update). Mode A and Mode B share ports. |
| `error during connect` / `docker info` fails | Docker Desktop not running | Start Docker Desktop, wait until "Engine running", retry. |
| `required variable ... run scripts/dev-setup.ps1` | No `.env` | Run `scripts/dev-setup.ps1`. |
| Maven: `RequireJavaVersion failed` | JDK < 25 on `JAVA_HOME` | `$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'` (current shell only). |
| Maven warnings `HTTP Status: 401` for `flyway-community-db-support` / `MavenFeed` | Extra repositories in your **user** `~/.m2/settings.xml` | Harmless for this build; remove those repositories from your settings if noisy. |
| `'mvnw.cmd' is not recognized` | Running an old copy of `tools/mvnw.mjs` | The launcher calls `.\mvnw.cmd`; pull latest. |
| Testcontainers test skipped | Docker not reachable from Java | Start Docker; the test is `@Testcontainers(disabledWithoutDocker = true)`. |
| Node engine errors | Node < 24 | Install Node 24 LTS (nvm-windows/fnm), keep the machine default untouched if other projects need it. |
| `os error 1314` during `next build` | Standalone output needs symlink privilege on Windows | Host builds don't use standalone; only the Docker build sets `NEXT_OUTPUT=standalone`. Don't set it on the host. |
| `/usr/bin/env: 'sh\r'` or init script fails | CRLF line endings in `*.sh` / `mvnw` | `.gitattributes` forces LF; re-checkout or `git add --renormalize .`. |
| Stale containers / old image after code change | Image not rebuilt | `pnpm stack:up` uses `--build`; or `docker compose ... up --build -d --force-recreate <service>`. |
| `flyway-migration` exits 1 with *checksum mismatch* | An already-applied migration file was edited | Revert the edit and add a new `V<n>__...sql`. Local-only last resort: dev reset in the runbook. |
| Flyway: `permission denied` | Role mismatch — migrations must run as `platform_migrator` | Check `FLYWAY_USER` / `.env`; roles are created only on first volume init. |
| Backend `password authentication failed` | `.env` passwords changed after the DB volume was created | Restore the old `.env` or `ALTER ROLE ... PASSWORD` as admin, or use the dev reset. |
| Dashboard shows *Backend API: Unavailable* | Backend down or CORS origin mismatch | `curl.exe http://localhost:8080/api/v1/status`; check browser console; `APP_CORS_ALLOWED_ORIGINS`. |
| Prometheus target `host` DOWN | Backend not running on host (expected in Mode B before publishing, or Mode A stopped) | Start the backend; `max(up)` is what matters. |
| No traces/logs in Grafana | Collector not reachable from the backend | Mode A: `http://localhost:4318`; Mode B: `http://otel-collector:4318`; `curl.exe http://localhost:13133/`. |
| Grafana login fails | Password comes from `.env` at **first** start of the grafana volume | Use the original value, or `docker compose exec grafana grafana cli admin reset-admin-password <new>`. |
| Backend fails at startup: `CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY must be set` | `.env` predates the chat MVP | Rerun `scripts/dev-setup.ps1` (appends missing keys, keeps existing values), then `pnpm stack:up` (recreates Centrifugo with the new secret). |
| Chat badge stuck on *Connecting/Reconnecting* | Centrifugo down, wrong `PUBLIC_CENTRIFUGO_WS_URL`, or origin not in `infra/centrifugo/config.json` `allowed_origins` | `curl.exe http://localhost:8000/health`; `docker compose logs centrifugo` (look for `invalid token`/origin errors); keep URLs on `localhost`. |
| *Subscription ... rejected: permission denied (103)* | Subscription token for a channel the actor may not use, or missing subscription token | Only agentbob may use `chat:inbox`; userone only its own conversation. |
| Message shows *saved, but live delivery failed* | Centrifugo publish failed after commit (`realtimeDelivery: FAILED`) | Message is safely stored; the other side sees it after reload/reconnect. Check backend logs for `Centrifugo publish ... failed`. |
| Chat counters in Grafana look doubled | Old Prometheus config scraping host + container | Recreate prometheus with `pnpm stack:up` (Mode B mounts `prometheus.containers.yml`). |
| Edited Grafana dashboard JSON not showing | Docker Desktop on Windows may not propagate bind-mount change events to Grafana's file provider | `docker compose restart grafana` (provisioned dashboards are read-only in the UI by design). |
