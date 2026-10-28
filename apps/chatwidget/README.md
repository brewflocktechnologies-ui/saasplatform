# chatwidget

Lit 3 + Vite app containing the framework-independent `<chat-platform-widget>` custom element.

- `/` — overview + standalone `<cp-status-badge>`.
- `/demo` — live chat as demo visitor `userone` (widget session + tenant-scoped API): history from the API, live updates from Centrifugo, pending/retry sends, typing, agent presence, lifecycle status,
  connection state, send errors. **LOCAL POC — NOT REAL AUTHENTICATION.** See docs/features/local-chat-mvp.md.

Attributes: `widget-key` (default `VITE_PUBLIC_WIDGET_KEY` or `wk_local_demo`), `external-id`, `display-name` (contact identity), `api-base-url` (default `http://localhost:8080`), `realtime-url` (default
`ws://localhost:8000/connection/websocket`); build-time overrides `VITE_PUBLIC_API_BASE_URL`,
`VITE_PUBLIC_CENTRIFUGO_WS_URL`, `VITE_PUBLIC_WIDGET_KEY`. Never put secrets in `VITE_*`.

| Command | |
|---|---|
| `pnpm dev:widget` | Vite dev server on http://localhost:5173 (`/demo` for the chat) |
| `pnpm nx build chatwidget` | production bundle → `dist/apps/chatwidget` |
| `pnpm nx run chatwidget:preview` | serve the production bundle on :5173 |
| `pnpm nx test chatwidget` | Vitest (jsdom) component tests |

## Embed entry point (v1)

`src/embed/v1.ts` registers `<chat-platform-widget>` and `<cp-status-badge>` idempotently and is emitted as
`embed/chat-platform-widget.v1.js` (stable, versioned filename; other chunks are content-hashed):

```html
<link rel="stylesheet" href="<widget-origin>/assets/main-*.css" /> <!-- or your own copy of tokens.css -->
<script type="module" src="<widget-origin>/embed/chat-platform-widget.v1.js"></script>
<chat-platform-widget api-base-url="https://api.example" realtime-url="wss://rt.example/connection/websocket"></chat-platform-widget>
```

Breaking changes to the element API require a new `v2` entry. No CDN pipeline yet. The bundle contains no
secrets; the container (`Dockerfile`, unprivileged nginx on :8080) serves it with `Access-Control-Allow-Origin: *`
because module scripts are CORS-fetched when embedded.
