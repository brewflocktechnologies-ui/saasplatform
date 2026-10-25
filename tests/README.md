# tests (Nx project `platform-tests`)

Browser-level smoke tests and live API contract tests with Playwright (Chromium).

| Folder | What |
|---|---|
| `e2e/dashboard.spec.ts` | title + environment, real healthy status (backend/DB/Centrifugo + bootstrap record), failure state when the API is unreachable (requests aborted via `page.route`), link to the widget demo, no console errors |
| `e2e/widget.spec.ts` | custom element + greeting, sending a message updates the in-memory list, standalone `<cp-status-badge>`, tokens reach shadow DOM, no console errors |
| `contract/platform-api.spec.ts` | live `/api/v1/*` responses validated against `libs/contracts/openapi/platform-api.yaml`; problem+json errors; actuator health |

## Run

Prerequisite: infrastructure + backend running (Mode A or B). Then:

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
