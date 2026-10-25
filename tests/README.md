# tests (Nx project `platform-tests`)

Browser-level smoke tests and live API contract tests with Playwright (Chromium).

| Folder | What |
|---|---|
| `e2e/dashboard.spec.ts` | title + environment, real healthy status (backend/DB/Centrifugo + bootstrap record), failure state when the API is unreachable (requests aborted via `page.route`), link to the widget demo, no console errors |
| `e2e/chat-mvp.spec.ts` | **two browser contexts**: widget `/demo` (userone) + dashboard `/agent` (agentbob), both Live and subscribed; message each way appears **without reload**; then reload both and assert history; read-only PostgreSQL query (`support/db.ts`) proves both rows, sender roles and one conversation id; no console errors |
| `e2e/widget.spec.ts` | overview + standalone `<cp-status-badge>`, `/demo` renders as userone with POC notice and Live state, blank message blocked, tokens reach shadow DOM, no console errors |
| `contract/platform-api.spec.ts` | live `/api/v1/*` (incl. `/demo`) responses validated against the OpenAPI/JSON Schemas; status codes for invalid input/actors/channels; real Centrifugo subscription receives `message.created` validated against the JSON Schema; invalid connection token, token-less subscription and client publish are rejected by Centrifugo |

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
