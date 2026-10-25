# chat-client (`@chat-platform/chat-client`)

Framework-agnostic client for the chat MVP, shared by `chatdashboardapp` (React) and `chatwidget` (Lit) without
sharing any UI runtime (Nx tags `type:data-access`, `platform:shared`).

- `createChatApi(baseUrl)` — typed calls to `/api/v1/demo/*`; errors become `ApiError(status, detail)` (0 = unreachable).
- `createRealtime({ url, api, actorId, onState })` — official `centrifuge` SDK; connection and per-channel
  subscription tokens are fetched from the backend; `subscribe(channel, { onEvent, onSubscribed })` returns a cleanup.
  `onSubscribed` fires again after reconnects: reload history there.
- `mergeMessages(existing, incoming)` — dedupe by message id, order by `createdAt`, `id`.
- Types/channel helpers mirror `libs/contracts`; the spec validates them against the JSON Schemas.

LOCAL POC — the `actorId` is not authentication. Tests: `pnpm nx test chat-client`.
