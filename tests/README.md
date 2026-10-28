# tests (Nx project `platform-tests`)

Browser-level smoke tests and live API contract tests with Playwright (Chromium).

| Folder | What |
|---|---|
| `e2e/dashboard.spec.ts` | title + environment, real healthy status (backend/DB/Centrifugo + bootstrap record), failure state when the API is unreachable (requests aborted via `page.route`), link to the widget demo, no console errors |
| `e2e/conversation-platform.spec.ts` | master spec 12.3 in **two browser contexts** (widget + dashboard): presence, queue, assignment, live messages both ways, typing, reload, resolve/auto-reopen/archive/unarchive/reopen, archived 409 → new conversation, retention dry run + purge; persisted state asserted through the API and PostgreSQL (`support/db.ts`) |
| `e2e/resilience.spec.ts` | reload mid-conversation, message during bootstrap, Centrifugo restart converges (needs docker), timeout + retry creates one row, lost typing-stop, stale presence expiry, archived/reopened lists, non-participant access |
| `e2e/widget.spec.ts` | overview + standalone `<cp-status-badge>`, `/demo` renders as userone with POC notice and Live state, blank message blocked, tokens reach shadow DOM, no console errors |
| `contract/platform-api.spec.ts` | live `/api/v1/*` responses (status, widget session/conversation, message page, agent inbox, problems with `code`) validated against the OpenAPI/JSON Schemas; real Centrifugo events (`message.created`, `conversation.updated`, `typing`) validated against the event schemas; invalid connection token and foreign-channel subscription are rejected; helpers in `support/api.ts` |

## Run

Prerequisite: infrastructure + backend running (Mode A or B) and a repo-root `.env` (the DB assertion reads the
app-role credentials from it; read-only session, no secrets logged). Then:

```powershell
pnpm exec playwright install chromium   # once (dev-setup.ps1 does this)
pnpm e2e                                 # = nx run platform-tests:e2e
```

Web-server lifecycle (`playwright.config.ts`): if something already answers on `http://localhost:3000` / `:5173`
(dev servers or containers) it is reused; otherwise Playwright starts production builds via
`pnpm nx run chatdashboardapp:start` and `pnpm nx run chatwidget:preview`. URLs can be overridden with
`DASHBOARD_URL`, `WIDGET_URL`, `API_URL`. Reports: `dist/.playwright/tests/report`.

Unit/component tests live next to each project (Vitest) and in `services/modulithplatformservice/src/test` (JUnit,
Testcontainers).


The `lifecycle` and `resilience` Playwright projects run serially after the parallel `chromium` project (the lifecycle test purges all resolved conversations of the demo tenant; resilience stops Centrifugo).
