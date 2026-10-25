# chatwidget

Lit 3 + Vite app containing the framework-independent `<chat-platform-widget>` custom element and its demo page.
Local POC only: messages are kept in memory and never sent to a server.

| Command | |
|---|---|
| `pnpm dev:widget` | Vite dev server on http://localhost:5173 |
| `pnpm nx build chatwidget` | production bundle → `dist/apps/chatwidget` |
| `pnpm nx run chatwidget:preview` | serve the production bundle on :5173 |
| `pnpm nx test chatwidget` | Vitest (jsdom) component tests |

## Embed entry point (v1)

`src/embed/v1.ts` registers `<chat-platform-widget>` and `<cp-status-badge>` idempotently and is emitted as
`embed/chat-platform-widget.v1.js` (stable, versioned filename; other chunks are content-hashed):

```html
<link rel="stylesheet" href="<widget-origin>/assets/main-*.css" /> <!-- or your own copy of tokens.css -->
<script type="module" src="<widget-origin>/embed/chat-platform-widget.v1.js"></script>
<chat-platform-widget greeting="Hello!"></chat-platform-widget>
```

Breaking changes to the element API require a new `v2` entry. No CDN pipeline yet. The bundle contains no
secrets; the container (`Dockerfile`, unprivileged nginx on :8080) serves it with `Access-Control-Allow-Origin: *`
because module scripts are CORS-fetched when embedded.
