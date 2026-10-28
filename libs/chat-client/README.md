# chat-client (`@chat-platform/chat-client`)

Framework-agnostic conversation-platform client, shared by `chatdashboardapp` (React) and `chatwidget` (Lit) without
sharing any UI runtime (Nx tags `type:data-access`, `platform:shared`).

- `createWidgetApi(baseUrl, tokenProvider)` / `createAgentApi(baseUrl, tokenProvider)` — typed, bearer-authenticated
  calls; errors become `ApiError` (`status`, `code`, `traceId`; status 0 = unreachable, 8 s client timeout).
- `createRealtime({ url, getConnectionToken, getSubscriptionToken, onState })` — official `centrifuge` SDK;
  `subscribe(channel, { onEvent, onSubscribed({ wasRecovering, recovered }), onError })` returns a cleanup.
- `createConversationSync(...)` — subscribe → catch-up by `sequence` (max 5 pages, then newest-page reload) → gap
  detection → dedupe by `eventId`/id; conversation events follow the version rule. `subscribed` always reconciles.
- `createPendingQueue(...)` — optimistic sends with a stable `clientMessageId`, reused on retry.
- `createTypingEmitter` / `createTypingReceiver` — throttled typing signals and timeout-cleared receiver.
- `createWidgetSessionStore` / `ensureSession` — storage-safe widget session with refresh on 401/404.
- `mergeMessages(current, incoming)` — dedupe by id, order by `sequence`, then `id`.
- Types/channel helpers mirror `libs/contracts`; `contracts.spec.ts` validates fixtures against the JSON Schemas.

Agent sign-in via `localLogin` is a LOCAL POC — NOT REAL AUTHENTICATION. Tests: `pnpm nx test chat-client`.
