# Chat MVP — validation report

- **Spec:** `specs/mvpsetup.spec.md` (end-to-end local chat: `userone` widget ↔ `agentbob` dashboard)
- **Date:** 2026-10-25 · **Base commit:** `4d0bf2a` (initial foundation); MVP changes are **uncommitted** in the working tree.
- **Environment:** Windows 11, Docker Desktop 29.9.2 / Compose v5.6.1, Node 24.18.0, pnpm 10.11.0, JDK 25.0.3
  (`JAVA_HOME` per process), Spring Boot 4.1.2, Centrifugo v6.10.2, `centrifuge` JS SDK 5.8.0, PostgreSQL 18.6.
- **Result:** every acceptance item PASS (two-browser live delivery both ways, persistence, refresh/restart
  recovery, two-context Playwright with DB assertion). Final `pnpm verify`: **31/31 PASS, exit 0**. Stack was left
  **running in Mode B** at handover.

Legend: PASS = executed and inspected · FAIL · BLOCKED · NOT RUN.

## 1. What was implemented (paths)

| Area | Paths |
|---|---|
| Migration | `database/flywayMigration/sql/V2__create_chat_tables.sql` (V1 untouched) |
| Backend `chat` module | `services/modulithplatformservice/src/main/java/com/byteflock/chatplatform/chat/` — `DemoActor`, `ChatDtos`, `ChatRepository`, `ChatService`, `ChatChannels`, `ChatController`, `RealtimeTokenController` |
| Backend `realtime` module | `.../realtime/` — `RealtimeTokenIssuer` (Nimbus JOSE 10.10, HS256), `RealtimePublisher`, `IssuedToken`; `shared/ClockConfig`; CORS now GET+POST; health check uses `app.centrifugo.url` |
| Backend config/tests | `application.yml` (`app.centrifugo.*`), `pom.xml` (nimbus); tests `chat/ChatServiceTests`, `chat/ChatIntegrationTests`, `realtime/RealtimeTokenIssuerTests`, `realtime/RealtimePublisherTests`, `CentrifugoStub` |
| Contracts | `libs/contracts/openapi/platform-api.yaml` (v0.2.0), `asyncapi/chat-realtime.yaml`, `json-schema/chat/*.schema.json`, `tools/validate-json-schemas.mjs`, `libs/contracts/project.json` |
| Shared client | `libs/chat-client` (new Nx project, tags `type:data-access`, `platform:shared`); ESLint boundary rule added |
| Widget | `apps/chatwidget/demo/index.html` (`/demo`), `src/chat-platform-widget.ts` (API + Centrifugo), `index.html`, `vite.config.mts` (MPA), `nginx.conf` (`/demo` without redirect), spec |
| Dashboard | `apps/chatdashboardapp/src/app/agent/{page,agent-inbox}.tsx` (`/agent`), `page.tsx` links, `global.css` |
| Infra | `infra/centrifugo/config.json` (namespace `chat`, subscription tokens), `docker-compose.yml`/`.apps.yml` env, `infra/observability/prometheus.containers.yml` + Mode-B mount (fixes double scrape), Grafana chat panels |
| Setup | `.env.example` (+ `CENTRIFUGO_CLIENT_SUBSCRIPTION_TOKEN_HMAC_SECRET_KEY`, `PUBLIC_CENTRIFUGO_WS_URL`), `scripts/dev-setup.ps1` (appends missing keys to an existing `.env` without changing values), `scripts/verify-local.ps1` (chat checks) |
| Tests | `tests/e2e/chat-mvp.spec.ts`, `tests/support/db.ts`, `tests/e2e/widget.spec.ts`, `tests/e2e/dashboard.spec.ts`, `tests/contract/platform-api.spec.ts`, `tests/eslint.config.mjs` |
| Docs | `docs/features/local-chat-mvp.md`, README, local-development, architecture, tool-versions, troubleshooting, ADR-0004, lib/test/infra READMEs |
| CI fix | `services/modulithplatformservice/mvnw` executable bit set in the Git index (see §6) |

## 2. API operations and channels

`POST /api/v1/demo/conversations` (201 created / 200 reused) · `GET /api/v1/demo/conversations?actorId=agentbob` ·
`GET|POST /api/v1/demo/conversations/{id}/messages` · `GET /api/v1/demo/realtime/connection-token` ·
`GET /api/v1/demo/realtime/subscription-token`. Channels `chat:conversation:<uuid>` (`message.created`) and
`chat:inbox` (`conversation.created`, `message.created`). Details: [local-chat-mvp.md](../features/local-chat-mvp.md).

## 3. Run, URLs, inspection

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev-setup.ps1   # once / after pulling (appends new .env keys)
pnpm stack:up      # docker compose -f docker-compose.yml -f docker-compose.apps.yml up --build -d --wait
pnpm stack:down    # stop, keeps volumes; restart = pnpm stack:up
docker compose -f docker-compose.yml -f docker-compose.apps.yml logs -f modulithplatformservice centrifugo postgres
```

Browser A → http://localhost:5173/demo (visitor `userone`) · Browser B → http://localhost:3000/agent (agent
`agentbob`) · API http://localhost:8080 · Centrifugo `ws://localhost:8000/connection/websocket` · Grafana
http://localhost:3001 · Prometheus http://localhost:9090. Host hot reload: `pnpm dev:backend`, `pnpm dev:dashboard`,
`pnpm dev:widget`. DB rows without secrets: query in [local-chat-mvp.md](../features/local-chat-mvp.md#data-model-databaseflywaymigrationsqlv2__create_chat_tablessql).

## 4. Commands executed and results

| # | Command / check | Result |
|---|---|---|
| A1 | Read spec, base spec, AGENTS.md, README, architecture, Compose, manifests; `centrifugo defaultconfig -d` / `defaultenv` (v6.10.2); `npm view centrifuge`; Maven Central nimbus metadata | PASS — config keys and versions confirmed (see tool-versions.md) |
| A2 | Baseline before changes | NOT RUN in this session; last baseline = previous session's `pnpm verify` 27/27 PASS on `4d0bf2a`. GitHub CI on `4d0bf2a` had **failed** (pre-existing, §6) |
| B1 | `./mvnw -B test` (JDK 25) — first run | FAIL 25/26: old `BootstrapIntegrationTests` asserted `schema version 1` (now 2) → made version-agnostic |
| B2 | `./mvnw -B test` | **PASS 26/26** (ChatIntegrationTests 7 on Testcontainers PG 18 + real V1/V2, ChatServiceTests 7, publisher 3, token issuer 3, status 3, bootstrap 2, Modulith verify 1) |
| B3 | `pnpm contracts:validate` | PASS — OpenAPI + AsyncAPI valid, 4 JSON Schemas strict-compiled, examples valid |
| B4 | `scripts/dev-setup.ps1 -SkipInstall` ×2 on existing `.env` | PASS — 1st: appended 2 keys, existing lines unchanged (diff verified, values redacted); 2nd: no change |
| B5 | `nx run-many -t test lint typecheck build` for chat-client, chatwidget, chatdashboardapp | PASS after fixing 3 lint errors (empty mock methods) and 1 lint warning (custom assert helpers) |
| C1 | `docker compose -f … config -q`; `… up --build -d --wait` | PASS — Flyway "Successfully validated 2 migrations … now at version v2"; all services healthy |
| C2 | psql: history, indexes, constraints, `platform_app` privileges | PASS — V1+V2 success; `ux_chat_conversations_open_visitor`, `ix_chat_messages_conversation_created`, body/role/status checks, FK present; app role INSERT=t, CREATE on schema=f |
| D1 | `pnpm e2e` — first run | FAIL 18/19: token-less subscription test waited for SDK `error`, but SDK emits `unsubscribed` (103) → test fixed; `chat-client` now surfaces such rejections to the UI |
| D2 | `pnpm e2e` after image rebuild (`--reporter=list`) | **PASS 19/19**, incl. `chat-mvp.spec.ts` (two contexts, both directions without reload, reload, DB rows) |
| D3 | Manual check in the built-in browser: tab A `/demo`, tab B `/agent` (same browser profile, separate tabs) | PASS — visitor message appeared in agent tab without reload; agent reply appeared in widget without reload; widget `Live`, `subscribed=true` |
| D4 | Stop Centrifugo → `POST …/messages` as agentbob → start Centrifugo | PASS — HTTP 201 `realtimeDelivery=FAILED`; logs "Centrifugo publish … failed: HttpConnectTimeoutException" + "Message persisted but live delivery failed … message=<id>" (no body); row present in DB; widget `Reconnecting` → `Live` and back-filled the message from the API |
| D5 | Restart backend + both frontend containers; GET history | PASS — 20 messages incl. manual ones returned |
| E1 | Prometheus queries after chat traffic | PASS — `http_server_requests_seconds_count` per `/api/v1/demo/*` URI and status (201/400/403/404); `chat_messages_persisted_total{role}`; `chat_realtime_publications_total{outcome}` incl. 2 failures from D4 |
| E2 | Found: every series duplicated in Mode B (host + container targets scrape the same process) | FIXED — Mode B mounts `prometheus.containers.yml`; targets now: one backend target; DB confirmed only 1 conversation exists (the "2×201" was the duplicate) |
| E3 | Tempo `GET /api/v2/traces/<X-Trace-Id>` for a POST message | PASS — span `http post /api/v1/demo/conversations/{conversationId}/messages` |
| E4 | Loki `{service_name="modulithplatformservice"} \| trace_id="<id>"` / `\|= "observability-probe"` | PASS — "Message persisted conversation=… message=… sender=userone"; message body found in logs: **0** |
| E5 | Grafana dashboard API | PASS after `docker compose restart grafana` (file change not picked up on Windows bind mount) — 3 chat panels provisioned |
| F1 | Secret scan: `.env` Centrifugo keys + DB passwords vs files in running `chatwidget` and `chatdashboardapp` containers; verify script scan of `dist`, `.next/static` and all committable files | PASS — 0 hits |
| F2 | `pnpm verify` (final) | **PASS 31/31, exit 0** |
| F3 | `gh run view 40189491223` (CI on `4d0bf2a`) | FAIL (pre-existing): `./mvnw: Permission denied` → fixed via `git update-index --chmod=+x` |
| F4 | GitHub CI run `40191724736` on `00cbb6d` (MVP commit) | **PASS** — Frontend, Contracts + Compose, Backend (JDK 25, Testcontainers), E2E on the full container stack: all success |

## 5. Acceptance checklist

| Item | Result | Evidence |
|---|---|---|
| Repo and setup docs inspected before editing | PASS | A1 |
| Next Flyway migration added; existing not modified | PASS | V2 only; Flyway validate PASS (checksums) |
| userone creates/reuses a persistent conversation | PASS | integration test idempotency; contract test 201→200 same id; DB 1 OPEN row |
| agentbob sees it through the API | PASS | inbox endpoint + E2E inbox item with same id |
| Backend-issued Centrifugo tokens | PASS | token endpoints; SDK `getToken` callbacks only |
| Unauthorized actor/channel token requests rejected | PASS | 400/403/404 in integration + contract tests |
| Widget → API → PG → Centrifugo → dashboard, no refresh | PASS | E2E + manual D3 |
| Dashboard → … → widget, no refresh | PASS | E2E + manual D3 |
| Both persist and remain after reload | PASS | E2E reload + D5 |
| Client publish disabled; no secrets in bundles | PASS | live publish → 103; F1 |
| History API-backed; events deduplicated | PASS | `mergeMessages` tests; E2E `toHaveCount(1)` for sender's own message |
| Playwright two contexts + real DB assertion | PASS | `chat-mvp.spec.ts` with `tests/support/db.ts` |
| Compose health, migrations, startup reproducible | PASS | C1, D5, F2 |
| Metrics/logs/traces verified | PASS | E1–E5 |
| Build/unit/integration/contract/E2E reported | PASS | §4 |
| README/local-development/report updated | PASS | §1 |
| Local-only auth limitations documented | PASS | README warning, banners on both pages, feature guide |

## 6. Pre-existing vs new failures

- **Pre-existing:** GitHub CI on `4d0bf2a` failed in the Backend job (`./mvnw: Permission denied`, exit 126) because the
  wrapper was committed from Windows without the executable bit; E2E job was skipped. Fixed in `00cbb6d`; CI run
  `40191724736` passed all four jobs.
- **New during this work (all fixed):** outdated schema-version assertion; 3 lint errors in a test mock; SDK
  `unsubscribed` vs `error` semantics; Prometheus double-scrape in Mode B; Grafana not reloading the dashboard file.

## 7. Known limitations

Hardcoded demo identities chosen by the client (`actorId`) — **no real authentication**; no transactional outbox
(at-most-once live delivery, recovered by history reload); single tenant, one open conversation, no assignment,
closure, typing, receipts or attachments; single-node in-memory Centrifugo without TLS; localhost-only CORS/origins;
no rate limiting. The manual check used two tabs of one browser profile (identities are not cookie-based, so this is
equivalent for this MVP), not two different browser products.

## 8. Required before UAT/production

Real identity (OIDC/session) replacing `DemoActor`, with authorization derived from the authenticated principal on
every API call and token request; tenant-scoped channels and data; transactional outbox; Centrifugo multi-node engine,
WSS/TLS and secret rotation; authenticated/hardened observability and actuator; rate limiting and abuse controls.

## 9. Next recommended feature spec

`specs/features/agent-authentication/feature.spec.md` — real agent sign-in (OIDC) and session-derived authorization
for the dashboard, API and Centrifugo tokens, replacing the `agentbob` stub (visitor identity for the widget as a
follow-up spec). Create it from `specs/features/_template/feature.spec.md`.
