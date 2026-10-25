# MVP Specification — End-to-End Local Chat (`mvpsetup.md`)

**Document type:** Executable feature specification for an AI coding agent  
**Status:** Ready for implementation after repository inspection  
**Target:** Extend the existing Nx monorepo setup into a working, database-backed, real-time chat MVP  
**Primary environment:** Local developer machine, Docker Desktop, JDK 25, Node.js already installed  
**Primary validation:** Two separate browser sessions — visitor `userone` in the Lit chat widget and agent `agentbob` in the Next.js dashboard — can exchange messages in real time through the backend API and Centrifugo, with every message persisted in PostgreSQL.

---

## 0. Agent execution contract — read this first

You are the implementation agent. You may be Claude Code or another coding agent that can inspect and edit the repository, run terminal commands, start containers, read logs and execute tests.

**Do not respond with a plan only. Implement this feature in the current repository, run it locally, verify it end to end, repair failures and provide evidence.** This is a specification for an executable result, not a design-only request.

### 0.1 Read the repository context first

1. Run `pwd` and inspect the repository tree and Git status. Do not create a second nested repository.
2. Read the original setup specification if present. Look for `initialdevsetup.spec.md` at the repository root or under `specs/`, as well as `AGENTS.md`, `CLAUDE.md`, `README.md`, `docs/architecture.md`, `docs/setup/local-development.md`, `docs/setup/tool-versions.md`, the existing `docker-compose.yml`, and existing project manifests.
3. Inspect the actual project names, Nx targets, package manager, Java build, ports, Compose files, environment variables, database migrations and observability stack. **Reuse and extend the existing implementation. Do not blindly overwrite files or duplicate infrastructure.**
4. Confirm the code builds before making changes if feasible. Record existing failures separately from failures introduced by this feature.
5. Check the current official documentation for the installed versions when configuring Centrifugo, its JavaScript SDK, Spring Boot, PostgreSQL, Flyway, Nx and Playwright. Do not copy configuration from an incompatible major version.
6. Treat this file as the source of truth for the chat MVP. If it conflicts with the initial setup spec's statement that chat is only a visual/in-memory demo, this spec supersedes that statement for chat functionality. Preserve the rest of the setup architecture and acceptance criteria.
7. Use the versions already pinned in the workspace unless they are incompatible. Make the smallest safe change needed. Do not perform broad dependency upgrades as part of this feature.
8. Run the services, tests and verifications yourself. Never mark a check PASS unless it was executed and the result inspected.
9. Do not delete source files, Git history, database volumes or user data to force a green result. Never run `docker compose down -v`, `git reset --hard` or broad cleanup commands without explicit authorization.
10. Do not stop at generated code. The required end state is a live, working, verified chat interaction, persisted messages and a handover report.

### 0.2 Required work gates

- **Gate A — Inspect:** repository, existing setup, ports and toolchain inspected.
- **Gate B — Specify/plan:** contracts, data model, real-time events and test plan reconciled with the repository.
- **Gate C — Implement:** migration, API, authorization stub, Centrifugo integration, widget and dashboard implemented.
- **Gate D — Run:** Compose dependencies and all required apps start successfully.
- **Gate E — Verify:** unit/integration/contract/E2E/manual checks run; DB persistence and live WebSocket delivery proved.
- **Gate F — Handover:** run instructions, limitations, actual results and next feature readiness documented.

Continue through all gates. If a genuine environment blocker prevents a check, report it accurately as BLOCKED and complete the other checks.

---

## 1. Product goal and scope

Build the smallest complete vertical slice of the LiveChat-style SaaS platform. There are exactly two hardcoded demo identities:

- **Visitor:** `userone`, using the Lit-based chat widget.
- **Agent:** `agentbob`, using the Next.js dashboard.

The two identities must be usable at the same time in two separate browsers or browser profiles on the same developer machine. Suggested manual arrangement:

- Browser A (for example Chrome): `http://localhost:5173/demo` — visitor `userone`.
- Browser B (for example Edge or a separate Chrome profile): `http://localhost:3000/agent` — agent `agentbob`.

Use the actual ports established by the existing repository and document them if different. Both pages must talk to the same backend and database.

### 1.1 User-visible happy path

1. Start the full local stack using the documented Docker Compose workflow.
2. Open the widget demo as `userone`.
3. The widget obtains or creates one active demo conversation for `userone`, loads prior messages from the API and connects to Centrifugo over WebSocket.
4. Open the agent dashboard as `agentbob`.
5. The dashboard loads the conversation list from the backend and connects to Centrifugo over WebSocket.
6. `userone` sends a message. The backend validates it, writes it to PostgreSQL, commits the transaction, and publishes a real-time event to Centrifugo. `agentbob` sees the new message without refreshing the page.
7. `agentbob` replies. The same API → PostgreSQL → Centrifugo path delivers the reply to `userone` without refreshing the widget.
8. Refresh either page. Message history is retrieved from PostgreSQL through the API and remains visible.
9. Automated E2E tests verify both directions in independent browser contexts and confirm messages exist in the database.
10. Local observability remains running and allows the developer to inspect backend logs, request metrics and, where configured in the base setup, traces.

### 1.2 Included in this MVP

- One fixed demo visitor, `userone`.
- One fixed demo agent, `agentbob`.
- One open/reused demo conversation for `userone` (avoid creating a new conversation on every page refresh).
- Send and receive text messages in both directions.
- Message persistence, history loading and live WebSocket delivery.
- Centrifugo used as the real-time transport; it is not the system of record.
- Application-generated connection and subscription JWTs, scoped to the demo user/channel.
- A database migration that is owned by the existing standalone Flyway migration runner.
- API and event contracts, tests and operating instructions.
- An explicit local-only security notice for the hardcoded identity stub.

### 1.3 Explicitly out of scope

Do not implement the following in this MVP:

- Real login, OAuth/OIDC, passwords, registration or production-grade identity management.
- Multiple tenants, multiple visitors, agent assignment workflows or configurable identities.
- Attachments, images, read receipts, typing indicators, presence, message editing/deletion, conversation closure, queues or push notifications.
- AI responses, AI agent orchestration, billing, analytics products or customer administration screens.
- A BFF, microservice extraction, Kubernetes, cloud provisioning or a CDN deployment pipeline.
- Client-side publication directly to Centrifugo.
- Treating Centrifugo history as the durable message database.

The fixed identities and token endpoints are **local POC behavior only**. Do not present them as real authentication or safe for UAT/production. Future real authentication must replace the demo identity resolver and revalidate authorization for every API operation and real-time subscription.

---

## 2. Architecture and message flow

Keep the architecture already established by the repository:

- `apps/chatwidget`: Lit + TypeScript widget and local demo page.
- `apps/chatdashboardapp`: Next.js + TypeScript operator dashboard.
- `services/modulithplatformservice`: Spring Boot modular monolith and authoritative HTTP API.
- `database/flywayMigration`: dedicated migration runner, independent of application startup.
- PostgreSQL: durable source of truth for conversations and messages.
- Centrifugo: real-time publish/subscribe delivery over WebSocket.
- `libs/designsystem/tokens` (or the existing equivalent): shared semantic CSS tokens.
- `libs/contracts/openapi`: API contracts; add event JSON Schemas and AsyncAPI documentation where helpful.
- Existing local observability stack: OpenTelemetry Collector, Prometheus, Grafana, Tempo and Loki, if already created by the initial setup spec.

Do not introduce another database, NATS, Redis, a second backend, or a new service registry.

### 2.1 Authoritative send path

```text
Widget (`userone`) or dashboard (`agentbob`)
        |
        | POST /api/v1/demo/conversations/{id}/messages
        v
Spring Boot API
  1. Validate demo actor and conversation membership
  2. Validate and normalize message body
  3. Insert message into PostgreSQL in a transaction
  4. Commit transaction
  5. Publish event via Centrifugo HTTP API
        |
        +-------------------------> PostgreSQL (durable message row)
        |
        v
Centrifugo publishes to `chat:conversation:<conversationId>`
        |
        +-------------------------> Widget receives message.created
        |
        +-------------------------> Dashboard receives message.created
```

The API response is not a substitute for real-time delivery. The sender can render its successful HTTP response immediately, but must deduplicate the corresponding WebSocket event by message ID. The other browser must receive the event from Centrifugo and update without a refresh.

### 2.2 Persistence is authoritative

- A message must be committed to PostgreSQL before the backend attempts to publish its real-time event.
- A database failure means the API must not publish an event for a message that was not committed.
- If Centrifugo publication fails after the database commit, do not delete or roll back the saved message. Log the failure with a correlation ID and return a response that clearly distinguishes persistence success from real-time notification failure, or use a documented error response that explicitly says the message was already persisted. Avoid a misleading generic failure that causes blind resubmission.
- The initial MVP does not require a transactional outbox, but document it as a follow-up before production reliability guarantees. The simple publish-after-commit implementation must not claim exactly-once event delivery.
- On page load and refresh, clients fetch messages through the HTTP API. On reconnection, they reload history to fill gaps. Do not depend on Centrifugo's transient channel history for persistence.

### 2.3 Keep the UI simple

Widget requirements:

- Header identifies the demo visitor as `userone` and labels the page as a local POC.
- Conversation messages show sender and time; the visitor's own messages are visually distinct from the agent's.
- Text input and Send button; Enter may submit if accessible keyboard behavior is preserved.
- Disable sending while a request is in flight; show clear API, WebSocket and send errors.
- Display connection state, for example `Connecting`, `Live`, `Reconnecting` or `Offline`.
- Load persisted history before/while connecting; merge real-time events without duplicate messages.
- After a send response, render the saved message even if its own WebSocket publication is delayed.

Dashboard requirements:

- Route `/agent` with a simple agent inbox, identifying the current demo agent as `agentbob`.
- Conversation list loaded from the backend, including visitor ID, latest message/time and open status.
- Select the single demo conversation to show messages.
- Live-updated message list and a reply composer.
- WebSocket connection state and useful failure messages.
- Refreshing the page restores the conversation and saved history.
- No fake conversations or messages hardcoded in UI state.

Use existing design-system tokens, maintain keyboard accessibility, readable contrast and responsive layout. A polished full chat-product design is not required; reliable behavior and debuggability are more important.

---

## 3. API-first contract

Before or while implementing controllers, update the existing OpenAPI contract at `libs/contracts/openapi/platform-api.yaml` or its repository-equivalent. Use the existing contract-generation/validation tooling if configured. Do not create a second OpenAPI file for the same service.

The final documented API must include at least the following operations. Names can change only if the agent updates the contract, implementation, frontend clients, tests and README together.

### 3.1 Endpoints

#### `GET /actuator/health`

Use the existing Spring Boot health endpoint. It must be usable for container readiness according to current project configuration. Do not expose environment secrets or stack traces.

#### `POST /api/v1/demo/conversations`

Ensure the single active local demo conversation for `userone` exists and return it. The request may have an empty body. Repeated requests must return the same open conversation rather than create a new one each time.

Expected response, illustrative:

```json
{
  "id": "<uuid>",
  "visitorId": "userone",
  "assignedAgentId": "agentbob",
  "status": "OPEN",
  "createdAt": "<ISO-8601 timestamp>"
}
```

Return an appropriate `201` for a newly created conversation and `200` when reusing one, or adopt another consistent documented convention. The API response must clearly identify the same conversation ID for both browser clients.

#### `GET /api/v1/demo/conversations?actorId=agentbob`

Return the demo agent's conversations from PostgreSQL, sorted by most recent activity. The agent inbox must not be a static fixture. In this MVP the response will normally contain one active conversation.

Only the explicitly supported demo actor values are accepted. A missing or unsupported actor receives a structured `400` or `403` response rather than silently falling back to an arbitrary user.

#### `GET /api/v1/demo/conversations/{conversationId}/messages?actorId={actorId}`

Return persisted messages in ascending creation order. Validate that `actorId` is exactly `userone` or `agentbob` and that this actor is a participant/authorized demo agent for the conversation. An invalid UUID, unknown conversation or invalid actor should return a documented 4xx status.

#### `POST /api/v1/demo/conversations/{conversationId}/messages`

Request example:

```json
{
  "actorId": "userone",
  "text": "Hello, can you help me?"
}
```

For the agent response, use `"actorId": "agentbob"`. Derive the role on the server; do not trust a client-supplied `role` field. The server must:

- Confirm the actor is one of the two hardcoded demo identities.
- Confirm `userone` belongs to the conversation as visitor or `agentbob` is the assigned demo agent.
- Trim text and reject empty/whitespace-only messages.
- Enforce a documented maximum length (recommended: 4,000 characters).
- Persist the message and assign its UUID on the backend.
- Return the persisted message DTO, including ID, conversation ID, sender ID, sender role, body and ISO-8601 creation time.
- Publish after database commit to the conversation channel, and to the inbox channel if needed to refresh the agent's last-message preview.
- Never allow browsers to publish directly to Centrifugo.

Use a consistent structured error body, preferably Problem Details (`application/problem+json`) if the service already supports it.

#### `GET /api/v1/demo/realtime/connection-token?actorId={actorId}`

Issue a short-lived Centrifugo connection JWT for only `userone` or `agentbob`. The JWT `sub` must match that actor ID and include a bounded expiration. The browser must obtain the token from this backend endpoint; never calculate signing tokens or embed the signing secret in JavaScript.

#### `GET /api/v1/demo/realtime/subscription-token?actorId={actorId}&channel={channel}`

Issue a short-lived subscription JWT only after validating the requested channel against the current actor and conversation:

- `agentbob` may subscribe to `chat:inbox` and the specific `chat:conversation:<conversationId>` channels for conversations assigned to the agent.
- `userone` may subscribe only to the `chat:conversation:<activeConversationId>` channel belonging to `userone`.
- Reject malformed channels, unknown conversations and cross-actor subscriptions.
- The token must be bound to both the requested actor (`sub`) and exact channel (`channel`) and must expire.

These endpoints intentionally use hardcoded demo actor IDs and are not authentication. The purpose is to keep Centrifugo's channel permissions explicit while demonstrating the shape of a future token-authenticated integration.

### 3.2 DTO and error conventions

- Use explicit request/response DTOs. Do not expose database entity objects directly.
- Use UUIDs for conversation and message IDs.
- Use ISO-8601 timestamps with an explicit timezone (prefer UTC from the server).
- Return JSON with `Content-Type: application/json` for successful endpoints.
- Reject invalid payloads with clear, bounded messages. Never include SQL, credentials, stack traces or JWT secrets in API errors.
- Make the CORS allowlist include only the actual local dashboard/widget browser origins. Do not set wildcard CORS as a permanent shortcut.
- Frontends should use an environment-configured **browser-visible** API base URL such as `http://localhost:8080`. Never expose a Compose-only hostname such as `modulithplatformservice` to a user's browser.

---

## 4. PostgreSQL data model and Flyway migration

Use the existing PostgreSQL instance and the existing standalone `flywayMigration` runner. Inspect the migration history table and last committed migration first. **Add the next versioned migration; do not edit any existing migration.** If the initial setup created a `V1` bootstrap migration, the chat schema will typically be `V2__create_chat_tables.sql`.

### 4.1 `chat_conversations`

Required logical columns:

- `id UUID PRIMARY KEY`
- `visitor_id VARCHAR(80) NOT NULL`
- `assigned_agent_id VARCHAR(80) NOT NULL`
- `status VARCHAR(20) NOT NULL` with a CHECK constraint for the supported state (initially `OPEN`; optionally support `CLOSED` only if useful)
- `created_at TIMESTAMPTZ NOT NULL`
- `updated_at TIMESTAMPTZ NOT NULL`
- Optional `last_message_at TIMESTAMPTZ` if useful for inbox ordering

The MVP must prevent multiple simultaneously open demo conversations for `userone`. A PostgreSQL partial unique index on `visitor_id` for `status = 'OPEN'` is acceptable. The creation implementation must handle a uniqueness race safely and then load/reuse the existing conversation.

### 4.2 `chat_messages`

Required logical columns:

- `id UUID PRIMARY KEY`
- `conversation_id UUID NOT NULL REFERENCES chat_conversations(id)`
- `sender_id VARCHAR(80) NOT NULL`
- `sender_role VARCHAR(20) NOT NULL CHECK (sender_role IN ('VISITOR', 'AGENT'))`
- `body TEXT NOT NULL` with a check that trimmed body is not empty and a maximum length policy enforced by the API
- `created_at TIMESTAMPTZ NOT NULL`

Add an index on `(conversation_id, created_at, id)` so the history query can retrieve a conversation's messages efficiently and deterministically. Keep schema names, constraints and repository queries consistent.

### 4.3 Database invariants

- A message must reference an existing conversation.
- All persisted identities in this demo must be `userone` or `agentbob` with the corresponding role.
- A user cannot post to a nonexistent conversation.
- Empty or whitespace-only bodies must never be persisted.
- Conversation and message timestamps are set by trusted backend/database code, not accepted from the browser.
- Reopening the widget must reuse the open conversation and load history.
- The runtime service must connect with a restricted application DB account. Only the migration runner should have DDL permissions where the existing setup supports separate credentials. If the current POC uses one credential for all connections, document and fix that separation if it can be done without breaking the local bootstrap.

### 4.4 Migration verification

The agent must execute the repository's actual migration commands or Compose migration job and verify:

1. PostgreSQL reaches a healthy state.
2. Flyway validation succeeds.
3. Migrations finish successfully.
4. The chat tables, indexes and constraints exist.
5. The Spring Boot service can read/write using the runtime configuration.

A successful container exit code alone is not enough if the migration did not target the database used by the application.

---

## 5. Centrifugo real-time design

Use the existing Centrifugo container/version where present. Check that the JavaScript package is the official `centrifuge` client SDK and that the chosen SDK version supports the Centrifugo server version. Do not implement the WebSocket protocol by hand.

### 5.1 Channels

Use namespaced channels such as:

- `chat:inbox` — agent inbox conversation-created and message-preview-refresh events.
- `chat:conversation:<conversationId>` — live events for the specific conversation.

Use the exact naming consistently in the API publisher, subscription-token validation, browser SDK and tests.

### 5.2 Token and permission configuration

Configure Centrifugo with separate secrets for:

- Connection JWT HMAC signing.
- Subscription JWT HMAC signing, if using the separate subscription-token mode supported by the selected version.
- The Centrifugo server HTTP API key.

Use only local-development secrets in ignored `.env` configuration. Add placeholder variable names to `.env.example`, never real secrets. Update the Centrifugo JSON/YAML configuration using the syntax for the pinned version.

Requirements:

- Set `client.allowed_origins` to the dashboard/widget demo origins actually used by the browser. Do not leave an unexplained wildcard.
- Require a valid connection token; do not use Centrifugo's insecure anonymous mode for this MVP.
- Use subscription-token authorization or another equally precise supported mechanism. A valid `userone` token must not grant access to `chat:inbox` or another visitor's conversation. `agentbob` may access only the intended demo inbox/conversation scope.
- Do not enable client-side publish permissions. Browsers can subscribe after authorization but cannot publish chat messages directly to Centrifugo.
- Keep the HTTP API key and both HMAC secrets backend-only. They must never appear in frontend `NEXT_PUBLIC_*`, Vite `VITE_*`, source maps, browser bundles or response payloads.
- Keep Centrifugo admin/inspection endpoints disabled or safely bound to loopback with generated local credentials. Do not publish admin credentials into Git.

Use the current Centrifugo documentation for the selected server version: client JWT authentication, subscription-token authentication, channel permissions, server HTTP API and browser SDK examples.

### 5.3 Backend publication

The backend publishes with Centrifugo's server HTTP API over the internal Compose network. It should call the configured API endpoint using the required API-key header and the official publish request schema.

Example logical publication shape (verify exact server API response/headers against the selected version):

```json
{
  "channel": "chat:conversation:<conversationId>",
  "data": {
    "type": "message.created",
    "conversationId": "<uuid>",
    "message": {
      "id": "<uuid>",
      "senderId": "userone",
      "senderRole": "VISITOR",
      "body": "Hello",
      "createdAt": "<ISO-8601 timestamp>"
    }
  }
}
```

For an agent-inbox refresh, publish a separate compact event to `chat:inbox`, for example `conversation.created` or `message.created`, containing the conversation ID and a safe preview DTO. The inbox event should not be used as the source of message history.

Publication must contain the ID generated by the database-backed API response so clients can deduplicate events. Log publication failures without logging secrets or entire user message bodies.

### 5.4 Browser integration

- Request a connection token from the backend.
- Connect to the browser-visible Centrifugo URL, normally `ws://localhost:8000/connection/websocket` for this local HTTP-only POC. Use the actual configured path/port for the current version.
- Subscribe to channels through the JavaScript SDK with a subscription-token callback that calls the backend's subscription-token endpoint for the exact channel.
- Handle connected/disconnected/reconnecting states and subscription errors.
- Clean up subscriptions and client connections on component unmount/page lifecycle changes.
- Reconnect through the SDK. When the connection is re-established, refetch conversation history to cover possible missed events.
- Deduplicate events by message UUID so HTTP response + WebSocket publication cannot render duplicates.
- Do not send message bodies directly through the Centrifugo client publication API.

### 5.5 Event types

Define and document at least:

`conversation.created`

- `type`
- `conversation` summary: `id`, `visitorId`, `assignedAgentId`, `status`, timestamps

`message.created`

- `type`
- `conversationId`
- `message`: `id`, `conversationId`, `senderId`, `senderRole`, `body`, `createdAt`

Validate events in backend/client tests against a committed JSON Schema or an equivalent shared typed contract. If AsyncAPI is already part of the repository's contract standard, document the channels and event payloads there. Do not create two competing definitions.

---

## 6. Frontend implementation requirements

### 6.1 Lit widget (`apps/chatwidget`)

Create or extend a dedicated route/page for the demo, such as `/demo`, that uses the real Lit Web Component implementation. Do not build a separate React imitation of the widget in the Next.js app.

On page start:

1. Show `Demo visitor: userone` and a non-production disclaimer.
2. Call `POST /api/v1/demo/conversations` to obtain/reuse the active conversation.
3. Load message history using the returned conversation ID.
4. Obtain a connection token for `userone` and connect to Centrifugo.
5. Request a subscription token and subscribe to `chat:conversation:<conversationId>`.
6. Render live messages and connection state.

On send:

1. Ignore empty input after trim and show an accessible validation message if appropriate.
2. POST the body and `actorId: "userone"` to the backend.
3. Render the returned persisted DTO.
4. Merge subsequent WebSocket events by ID rather than appending duplicate records.
5. If persistence fails, clearly show that the message was not sent. If persistence succeeds but real-time publish fails, show a non-destructive warning and retain the saved message.

Use shared semantic tokens from `designsystem`. Ensure the custom element can work independently of the Next.js runtime and is still buildable as a separate widget bundle.

### 6.2 Next.js agent dashboard (`apps/chatdashboardapp`)

Create or extend route `/agent`.

On page start:

1. Show `Demo agent: agentbob` and a non-production disclaimer.
2. Obtain a connection token for `agentbob`.
3. Subscribe to `chat:inbox` with a subscription token issued specifically for that channel.
4. Fetch the inbox from `GET /api/v1/demo/conversations?actorId=agentbob` after establishing the subscription, so an event that happens during startup is not lost.
5. When an inbox event arrives, refresh or update the inbox from the API.
6. When the user selects a conversation, fetch history and subscribe to `chat:conversation:<conversationId>` using a freshly requested subscription token.
7. On a new message event, update history, deduplicating by message ID.
8. On reply, call the backend with `actorId: "agentbob"`, show the saved response and wait for live updates in the visitor browser.

Do not hardcode chat messages or a fake inbox in the component. The only hardcoded values are the two specified demo identity IDs and local demo UI labels.

### 6.3 API URL and browser networking

Keep the browser API URL configurable by environment and use the browser-resolvable host (`localhost` in this local setup). Compose service hostnames are for service-to-service connections only. If Next.js has server-side code, ensure the browser-visible API configuration is not accidentally replaced with a hostname only resolvable inside Compose.

If CORS preflight fails, fix the backend allowlist for the actual local origins. Do not disable browser security or instruct the user to run a browser with web security disabled.

---

## 7. Observability and diagnostics

Reuse the observability stack established by `initialdevsetup.spec.md`. Do not introduce a competing logs/metrics/traces stack.

At minimum:

- Spring Boot logs identify API request failures and Centrifugo publish failures, include useful correlation/trace IDs where supported, and do not log JWT signing keys, API keys or message bodies by default.
- Existing Actuator/Prometheus instrumentation records request counts, latency and HTTP error metrics for the conversation/message endpoints.
- If the repository already has OpenTelemetry tracing configured, verify that HTTP API calls produce traces visible in the configured trace backend. If tracing is not yet wired, add the smallest supported instrumentation configuration compatible with the existing Spring Boot version, or clearly report that this specific trace requirement remains blocked.
- Grafana remains reachable at the locally documented URL and its existing datasources work.
- Include runbook notes for checking backend logs, Centrifugo logs, Prometheus targets/metrics and Tempo traces if available.
- Add a log/metric/trace correlation recipe to the handover report.

A running Grafana container alone does not prove observability works. Trigger chat API requests and verify actual data is present.

---

## 8. Automated verification requirements

Use the existing workspace scripts and Nx targets. Inspect available targets before inventing command names. Add named targets/scripts for the checks below if missing, and ensure README commands are real and executable.

### 8.1 Backend unit and integration tests

Cover at least:

- Valid message persists with correct sender and conversation ID.
- Whitespace-only message rejected.
- Message exceeding the maximum length rejected.
- Unknown actor rejected.
- Visitor/agent cannot send to an unknown conversation.
- Conversation creation is idempotent and returns the same open demo conversation on repeated calls.
- Message history is returned in deterministic ascending order.
- A DB failure does not result in a Centrifugo publication.
- The publisher sends the expected channel and event envelope.
- Token issuance rejects invalid actor IDs and unauthorized channels.

Prefer integration tests against disposable PostgreSQL/Testcontainers or the repository's existing reproducible integration-test approach. If Docker is unavailable to the test runner, implement tests that can run in the normal documented environment and accurately report any environment-specific BLOCKED status.

### 8.2 Contract tests

- Validate OpenAPI syntax and schemas.
- Validate request/response JSON shapes against the contract.
- Validate event payloads against their committed JSON Schemas or shared event types.
- Ensure the implemented endpoint status codes and principal response DTO fields match the published contract.
- Do not silently update the spec just to match incorrect implementation behavior; decide which source should change and record the decision.

### 8.3 Playwright end-to-end test

Add a Playwright E2E suite, preferably under `tests/e2e/` and registered in Nx or the repository's current task tooling.

The test must run against the actual running local stack. Create two independent browser contexts/pages (the automation equivalent of two separate browsers):

- Visitor page at the widget demo as `userone`.
- Agent page at `/agent` as `agentbob`.

Minimum E2E scenario:

1. Wait until the backend and both app pages are ready.
2. Wait until the visitor conversation is created/reused and appears in the agent inbox.
3. Send a unique visitor message such as `hello-from-userone-<test-run-id>`.
4. Assert it appears in the agent UI **without refreshing or reloading the agent page** within a bounded timeout.
5. Send a unique reply such as `reply-from-agentbob-<test-run-id>`.
6. Assert it appears in the widget UI **without refreshing or reloading the widget page** within a bounded timeout.
7. Refresh both pages, reload the persisted history, and assert both messages still appear.
8. Query the API or test database to confirm both rows exist in PostgreSQL with the expected conversation ID and sender IDs. Use a test-only DB assertion or database query; do not infer persistence solely from UI text.
9. Check browser console/page errors and fail on unexpected exceptions (allow only documented harmless development warnings).
10. Verify the relevant subscriptions connected successfully, or expose testable UI status indicators and assert them.

Do not use `page.reload()` as part of the live-delivery assertions. Reload is only allowed in the explicit persistence portion after both live deliveries have already been proved.

Ensure E2E runs are repeatable when messages from a prior run exist. Use unique message bodies and either a dedicated isolated test database/schema or clean test records in a controlled way. Do not automatically drop the developer's persistent database volume to make tests deterministic.

### 8.4 Manual two-browser test

Document these manual steps and actually execute them when a GUI/browser is available:

1. Open `http://localhost:5173/demo` in Browser A.
2. Open `http://localhost:3000/agent` in Browser B.
3. Send a visitor message, observe it in the agent page without refresh.
4. Reply from the agent, observe it in the widget without refresh.
5. Refresh each page and ensure messages remain.
6. Verify row persistence via a SQL query or an explicit dev-only diagnostic endpoint that does not expose secrets.
7. Disconnect/reconnect the widget or reload a page and ensure it loads history again.

If a GUI browser cannot be controlled by the agent, execute Playwright E2E instead and report manual validation as NOT RUN, not PASS.

---

## 9. Local run workflow and expected URLs

Preserve the existing Compose layout. If the initial setup provides split Compose files, continue to use them. The final README must give one simple recommended full-stack command plus a host-hot-reload alternative where supported.

The preferred full-container workflow should resemble:

```powershell
# From the repository root.
# First inspect the existing .env/.env.example instructions; do not overwrite secrets.
 docker compose config
 docker compose up --build -d
 docker compose ps
```

If Compose files are split, show the exact combined `-f` commands in the documentation. Run the Flyway job as an explicitly ordered one-off service/job where that matches the existing setup. Use Compose health conditions and migration dependencies; do not assume a running container is ready to accept requests.

Use actual repository Nx targets for builds and tests, for example:

```powershell
pnpm nx show projects
pnpm nx run-many -t build
pnpm nx run-many -t test
pnpm nx e2e <actual-e2e-project-name>
```

Those example commands are not a substitute for checking target names. Update documentation to use commands that actually exist.

Expected browser-facing URLs (use the actual verified ports if different):

- Widget demo: `http://localhost:5173/demo`
- Agent dashboard: `http://localhost:3000/agent`
- Backend health: `http://localhost:8080/actuator/health`
- Backend OpenAPI file: committed under `libs/contracts/openapi/` (and served endpoint only if the current service exposes one)
- Centrifugo: `http://localhost:8000` / WebSocket endpoint configured for the selected version
- Grafana: `http://localhost:3001`
- Prometheus: `http://localhost:9090`

Provide a PowerShell-friendly command to inspect logs, for example `docker compose logs -f modulithplatformservice centrifugo postgres`, adjusted to the actual Compose service names.

### 9.1 Startup ordering

The expected sequence is:

1. PostgreSQL becomes healthy.
2. Flyway validation and migration run and exit successfully.
3. Centrifugo becomes ready.
4. Spring Boot starts and passes health checks after migration has completed.
5. Dashboard/widget app containers start and pass their health checks if supported.
6. E2E tests begin only after the relevant URLs are ready.

Do not make application startup silently run DDL migrations. If application and migration runner race against each other, fix the Compose dependencies.

---

## 10. Security and local-only boundary

This MVP deliberately uses fixed demo identities, but still needs basic safe design practices.

- Label demo pages and documentation **LOCAL POC — NOT REAL AUTHENTICATION**.
- Never hardcode tokens, HMAC secrets, Centrifugo API key or database password in source code, front-end config or committed Compose files. Use an ignored `.env` and `.env.example` with placeholder values.
- Ensure the backend is the only component able to publish to Centrifugo. Do not expose server API credentials to browser JavaScript.
- Bind infrastructure dashboards/admin consoles to loopback where practical. Use explicit Centrifugo origins.
- Limit demo actor IDs and channel subscriptions as described in this spec, even though these controls are not a substitute for production authentication.
- Enforce conversation membership on all message/history routes and subscription token requests.
- Validate and size-limit all incoming message payloads.
- Do not include message text, signing secrets, API keys, authorization headers or JWTs in logs.
- Never use a frontend `actorId` or channel name alone as proof of real-world identity. This is only a hardcoded local stub and must be replaced before any shared environment.
- Add a README warning that this spec is not approval to deploy the demo authentication model to UAT or production.

---

## 11. Suggested implementation tasks

The agent may adjust task order based on the actual repository, but all outcomes below are required.

### Task 1 — Baseline and version inspection

- Read initial setup docs and repository instructions.
- Inspect Nx projects and existing Compose services.
- Verify Centrifugo server/client SDK compatibility.
- Confirm Java, Spring Boot and migration tool versions.
- Record plan and changed files in a brief feature work note or PR description.

### Task 2 — Data migration and persistence layer

- Add the next Flyway migration for conversations and messages.
- Add repositories/queries and persistence integration tests.
- Implement idempotent get-or-create for the active demo conversation.
- Confirm query ordering, constraints and indexes.

### Task 3 — REST API and contracts

- Update OpenAPI and any shared schema files.
- Implement conversation, message history, send-message and token endpoints.
- Add input validation, demo-actor validation and structured errors.
- Add backend unit/integration/contract tests.

### Task 4 — Centrifugo authorization and publisher

- Configure Centrifugo for connection token validation and scoped subscription tokens.
- Implement JWT creation using a maintained Java JWT library compatible with the project's dependency baseline. Do not write custom JWT serialization/signature code.
- Implement a server-only Centrifugo publisher with timeout/error handling.
- Publish after DB commit and include the saved message ID.
- Test allowed and rejected channels and the outgoing publish request.

### Task 5 — Widget implementation

- Make the Lit demo create/reuse the conversation and load real message history.
- Integrate official `centrifuge` browser SDK.
- Implement token flow, conversation subscription, message sending, deduplication, error states and reconnect history reload.

### Task 6 — Agent dashboard implementation

- Create the `/agent` view and load the real inbox.
- Subscribe to the inbox and selected conversation channels.
- Implement real history, send reply, live updates and clean subscription lifecycle.

### Task 7 — E2E, observability and runbook

- Add Playwright two-context E2E coverage.
- Verify migrations, live delivery, persistence after refresh and browser error logs.
- Verify metrics/logs/traces in the existing stack; fix or document gaps.
- Update README/local-development instructions and troubleshooting.

### Task 8 — Full validation and handover

- Run formatting/lint/type checks/builds/tests/contracts/E2E as available.
- Start/restart with Compose using the documented workflow.
- Inspect actual database rows after E2E.
- Generate `docs/setup/mvp-validation-report.md` with evidence and limitations.

---

## 12. Validation commands and proof requirements

Use exact command names from the repository. At minimum, attempt the following categories and record actual results:

### Static and build checks

- Workspace project listing.
- Frontend lint, type-check, unit test and production build.
- Java compile, unit test and package via Maven Wrapper.
- OpenAPI/JSON Schema validation.
- Docker Compose configuration validation.
- Docker image builds for each changed deployable application.

### Runtime smoke checks

- PostgreSQL and Centrifugo health/readiness.
- Flyway `info`, `validate` and `migrate` (according to the actual runner scripts).
- Backend health endpoint.
- Conversation create/reuse endpoint called twice and returned IDs compared.
- Agent inbox endpoint returns the same conversation.
- Empty-body and invalid-actor requests fail as expected.
- Message POST persists and returns a backend-created UUID.

### Real-time check

- Two independent browser contexts connect through the browser-visible Centrifugo URL.
- The widget visitor message appears in the agent dashboard without reload.
- The agent reply appears in the widget without reload.
- At least one `message.created` event is observed in each appropriate subscriber/client path.
- Invalid actor/channel subscription-token requests are rejected.

### Persistence check

- Refresh both pages and verify complete history is restored from the API.
- Verify the same two unique test messages appear in database rows under one conversation ID.
- Verify sender IDs/roles map correctly: `userone` → `VISITOR`, `agentbob` → `AGENT`.
- Verify a backend/database error does not produce a false successful live event.

### Observability check

- Trigger requests to create/send messages.
- Confirm backend logs include relevant endpoint/action and request/trace correlation information without secrets.
- Confirm Prometheus metrics increment.
- Confirm trace visibility in Tempo only if tracing is configured; if not, identify the missing configuration and either implement it or mark precisely which trace test remains blocked.
- Confirm Grafana provisioning is functional.

---

## 13. Required handover and documentation

Update the existing README and local development docs. Do not leave conflicting run commands in multiple files. Add a concise dedicated guide such as `docs/features/local-chat-mvp.md` and the validation report `docs/setup/mvp-validation-report.md`.

The handover must state:

1. What was implemented and which paths were changed.
2. Which API operations and event channels exist.
3. How to start, stop and restart the full stack using the actual Compose commands.
4. The exact browser URLs and which browser should represent `userone` vs. `agentbob`.
5. How to run tests, E2E and contract validation.
6. How to inspect PostgreSQL conversation/message rows without printing secrets.
7. How to inspect application and Centrifugo logs and telemetry.
8. Exact commands executed and test results (PASS / FAIL / BLOCKED / NOT RUN).
9. Any failures that existed before work started versus new failures.
10. Known limitations: hardcoded demo identities, no real authentication, no transactional outbox, no multi-tenancy, one open conversation, no production security claim.
11. What must change before a UAT/production deployment.
12. The next recommended product feature spec after this MVP.

Do not claim the app is running after the session if you have not verified its live status. If containers are stopped at handover, state that clearly and provide the exact start command.

---

## 14. Definition of Ready for the next feature

This MVP is complete only when the repository is ready for the next product feature specification. That means:

- The monorepo remains coherent and all project boundaries are respected.
- Local apps are independently buildable/deployable.
- Database schema changes are owned by versioned Flyway migrations.
- API and event contracts are documented and checked.
- Visitor and agent can exchange messages in both directions over Centrifugo WebSocket delivery.
- Messages are persisted by the backend in PostgreSQL and survive refresh/restart of frontend containers.
- Playwright verifies the complete user path using two independent browser contexts.
- Required tests/builds pass, or true blockers are explicitly recorded with evidence.
- Observability is exercised, not merely installed.
- Run instructions and the actual validation report are committed.
- Demo identity and authorization limitations are clearly marked.

The next feature must start with a separate specification and not be silently bundled into this MVP.

---

## 15. Agent prompts and operating commands

Use the following prompts with Claude Code (or adapt them to the installed agent commands). The prompts are execution instructions; the specification itself remains the source of requirements.

### Prompt A — Execute the MVP specification

```text
Read specs/mvpsetup.md completely, then read the repository's initialdevsetup.spec.md (at the root or under specs), AGENTS.md, CLAUDE.md, architecture docs and local run instructions. Inspect the actual workspace before editing. Implement the complete end-to-end chat MVP in this repository: userone in the Lit widget, agentbob in the Next.js dashboard, Spring Boot API, PostgreSQL/Flyway persistence and Centrifugo WebSocket delivery. Preserve the existing Nx monorepo and observability setup. Do not just produce a plan. Run the services and the automated checks, fix failures, and write docs/setup/mvp-validation-report.md with actual results. Never claim a check passed unless it ran. Do not deploy anything to a remote environment.
```

### Prompt B — Spec/plan/task phase before changing code (if using Spec Kit)

```text
Use the installed Spec Kit workflow for the requirements in specs/mvpsetup.md. Inspect the existing codebase first. Produce or update the feature specification, plan and task artifacts using the version's documented commands. Resolve conflicts with the base setup rather than generating a parallel architecture. Keep this feature limited to the two hardcoded demo identities, API-backed conversation/message history, PostgreSQL persistence and Centrifugo live delivery. Do not start implementation until the resulting scope and acceptance criteria are coherent; then continue to implementation after review.
```

### Prompt C — Focused debugging after an E2E failure

```text
The local chat MVP's end-to-end test failed. Reproduce the failure first and identify the first failing boundary: browser UI, HTTP API/CORS, database/migration, connection JWT, subscription authorization, Centrifugo publication, WebSocket delivery, or test orchestration. Inspect app/container logs and browser console/network details. Fix the root cause without weakening acceptance criteria or disabling browser security. Rerun the failed test, the related unit/integration tests and the complete chat E2E test. Record the cause and actual before/after results in docs/setup/mvp-validation-report.md.
```

### Prompt D — Independent verification/review agent

```text
Review the implementation against specs/mvpsetup.md without assuming the implementation agent is correct. Do not modify code initially. Verify the API contract, Flyway migration immutability, database constraints, actor/conversation validation, Centrifugo connection/subscription token handling, client-side publish permissions, message persistence-before-publication, live delivery in both directions, browser refresh/history, E2E evidence and observability. Run the relevant checks yourself. Return a severity-ranked list of gaps with file paths and test evidence. After approval to fix, address only the confirmed gaps and rerun the affected tests.
```

### Prompt E — Final acceptance audit

```text
Perform the final acceptance audit for specs/mvpsetup.md. Run the documented Compose startup/readiness, migration validation, all applicable builds/tests, contract validation and Playwright two-browser-context flow. Verify actual PostgreSQL rows and confirm both directions delivered over Centrifugo without page reload. Inspect logs and telemetry. Update docs/setup/mvp-validation-report.md with timestamp, environment/tool versions, exact commands, PASS/FAIL/BLOCKED/NOT RUN results, evidence and known limitations. Do not overstate any unexecuted check. Leave the repository ready for the next independent feature spec.
```

---

## 16. Research and official references

The agent must verify the versions and exact configuration for the software already used by the repository. Start with these official sources; versioned documentation takes precedence over blog posts and old examples.

- **Centrifugo quickstart:** https://centrifugal.dev/docs/getting-started/quickstart
- **Centrifugo integration guidance:** https://centrifugal.dev/docs/getting-started/integration
- **Centrifugo client JWT authentication:** https://centrifugal.dev/docs/server/authentication
- **Centrifugo subscription JWT authentication:** https://centrifugal.dev/docs/server/channel_token_auth
- **Centrifugo channel permissions and namespaces:** https://centrifugal.dev/docs/server/channel_permissions
- **Centrifugo server HTTP API:** https://centrifugal.dev/docs/server/server_api
- **Centrifugo channel model:** https://centrifugal.dev/docs/server/channels
- **Official JavaScript SDK (`centrifuge`):** https://www.npmjs.com/package/centrifuge
- **Spring Boot system requirements:** https://docs.spring.io/spring-boot/system-requirements.html
- **Nx Java/Maven integration:** https://nx.dev/docs/technologies/java/maven/introduction
- **Lit documentation:** https://lit.dev/docs/getting-started/
- **Flyway documentation:** https://documentation.red-gate.com/flyway/
- **Docker Compose startup order and health checks:** https://docs.docker.com/compose/how-tos/startup-order/
- **Playwright documentation:** https://playwright.dev/docs/intro
- **GitHub Spec Kit (optional SDD workflow):** https://github.github.com/spec-kit/

### Technical notes the agent must respect

- Centrifugo's official integration guidance says the backend generates client connection tokens and owns the API key/token secret; the browser receives only short-lived tokens and uses an official client SDK.
- Centrifugo channel permissions default to deny in relevant configurations; use subscription tokens tied to the exact actor/channel rather than enabling broad public subscription or client publication to make the demo work quickly.
- Spring Boot and Nx versions move independently. Use the repository's pinned versions and official compatibility information; JDK 25 is installed, but that alone does not prove every dependency in the current repository supports Java 25.
- Docker Compose dependency order is not the same as service readiness. Gate startup on health checks and migration completion, then perform real HTTP/WebSocket checks.
- A green frontend test is not evidence of persistence; query the database or use a test DB assertion.
- A successful HTTP send from one browser is not proof of WebSocket delivery; the other browser must receive the event with no refresh.

---

# Final acceptance checklist

Mark each item only with recorded evidence:

- [ ] Existing repo and initial setup docs were inspected before editing.
- [ ] Correct next Flyway migration added; existing migrations not modified.
- [ ] `userone` can create/reuse a persistent conversation.
- [ ] `agentbob` sees the conversation through the backend API.
- [ ] Widget and dashboard obtain backend-issued Centrifugo tokens.
- [ ] Unauthorized actor/channel subscription-token requests are rejected.
- [ ] Widget → API → PostgreSQL → Centrifugo → dashboard works without refresh.
- [ ] Dashboard → API → PostgreSQL → Centrifugo → widget works without refresh.
- [ ] Both messages persist and remain after both pages reload.
- [ ] Client-side Centrifugo publication is disabled; secrets never ship to browser bundles.
- [ ] Message history is API/database-backed and real-time events are deduplicated.
- [ ] Playwright validates two independent browser contexts and actual DB persistence.
- [ ] Compose health, migrations and app startup are reproducible.
- [ ] Relevant metrics/logs/traces are verified or any precise gap is reported.
- [ ] Build, unit, integration, contract and E2E results are reported accurately.
- [ ] README/local-development guide and `docs/setup/mvp-validation-report.md` are updated.
- [ ] Local-only authentication limitations and production follow-up work are documented.

**Completion rule:** do not mark the MVP complete until both-direction live delivery, database persistence, refresh recovery and the two-context E2E scenario have been verified or a concrete environment blocker has been documented with evidence.
