# Conversation platform — local API guide

Status: **Plan 02 implemented** (schema V3, `tenancy`, `conversation` write path/lifecycle/history/read cursors, outbox
appender, widget and agent REST API). Still to come: outbox publisher and Centrifugo channel tokens (Plan 03),
allocation, presence and `/assign` (Plan 04), retention/admin (Plan 05), widget and dashboard on the new API (Plan 06),
load and final verification (Plan 07). Spec: [`specs/features/conversation-platform/feature.spec.md`](../../specs/features/conversation-platform/feature.spec.md).

Everything below runs against the local stack (`docker compose up -d --wait` plus `pnpm dev:backend`, or the full
container POC). The **LOCAL POC — NOT REAL AUTHENTICATION** identities are seeded by `LocalSeeder` in the `local`
profile: tenant `tenant-local`, inbox "Website" with widget key `wk_local_demo`, agent `agentbob`, contact `userone`.

## 1. Identity: two session tokens, one shape

Both principals use the same HS256 session JWT (`APP_SESSION_TOKEN_HMAC_SECRET_KEY`, 32+ characters), differing only in
the `typ` claim. The tenant always comes from the token; no endpoint accepts a tenant from the request.

```bash
# Visitor: public widget key → contact session (201), or refresh of a stored one (200)
curl -s -X POST http://localhost:8080/api/v1/widget/sessions \
  -H 'Content-Type: application/json' \
  -d '{"widgetKey":"wk_local_demo","externalId":"demo-visitor","displayName":"Ada"}'
# → {"sessionToken":"…","expiresAt":"…","tenantId":"tenant-local",
#    "inbox":{"id":"…","name":"Website"},"contact":{"id":"…","displayName":"Ada"}}

# Agent (LOCAL POC — NOT REAL AUTHENTICATION; only while APP_LOCAL_DEV_ENABLED=true)
curl -s -X POST http://localhost:8080/api/v1/agent/local-login \
  -H 'Content-Type: application/json' -d '{"agentId":"agentbob"}'
```

A contact session survives an expired token for up to 30 days after its **first** issue: send the stored token as
`sessionToken` to `POST /api/v1/widget/sessions` and the same contact is returned with a fresh `expiresAt` and the
original `auth_time`. After that window the endpoint answers 401 and the widget must start a new session.

## 2. Visitor flow

```bash
BASE=http://localhost:8080/api/v1
CONTACT_TOKEN=...   # from /widget/sessions

# Start or resume (201 created / 200 resumed; assignment.state = REQUESTED on create)
curl -s -X POST $BASE/widget/conversations -H "Authorization: Bearer $CONTACT_TOKEN"
C=/api/v1/widget/conversations/<conversationId>

# Send (201, or 200 when the same clientMessageId + body is replayed)
curl -s -X POST "http://localhost:8080$C/messages" -H "Authorization: Bearer $CONTACT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"clientMessageId":"0f4a2d1c-1b1e-4c8e-9d3a-1f2b3c4d5e6f","body":"Hello!"}'

# History: newest page, older pages, catch-up (limit clamped to 200; before+after is 400)
curl -s "http://localhost:8080$C/messages?limit=50" -H "Authorization: Bearer $CONTACT_TOKEN"
curl -s "http://localhost:8080$C/messages?before=71&limit=50" -H "Authorization: Bearer $CONTACT_TOKEN"
curl -s "http://localhost:8080$C/messages?after=100" -H "Authorization: Bearer $CONTACT_TOKEN"

# Read cursor (204; clamped to the head, never moves backwards; unread = lastSequence − cursor)
curl -s -X PUT "http://localhost:8080$C/read" -H "Authorization: Bearer $CONTACT_TOKEN" \
  -H 'Content-Type: application/json' -d '{"lastReadSequence":1}'
```

`clientMessageId` is mandatory and idempotent per `(conversation, sender type, sender id)`: the same id with the same
trimmed body returns the original message (200), a different body answers 409 `CLIENT_MESSAGE_ID_REUSED`. The request is
limited to 30 accepted sends per minute per principal (`RATE_LIMITED`).

The contact's `assignment` object always has exactly two keys — `state` and `agentId` (null when nobody holds the
conversation) — because the conversation channel is shared with the visitor. Agents see `decisionId`, `reason` and
`explanation` as well.

## 3. Agent flow

```bash
AGENT_TOKEN=...     # from /agent/local-login
A=http://localhost:8080/api/v1/agent

curl -s $A/inboxes -H "Authorization: Bearer $AGENT_TOKEN"
curl -s "$A/inboxes/<inboxId>/conversations?view=queue&limit=20" -H "Authorization: Bearer $AGENT_TOKEN"
curl -s "$A/inboxes/<inboxId>/conversations?view=mine&archived=false" -H "Authorization: Bearer $AGENT_TOKEN"
curl -s "$A/conversations/<conversationId>" -H "Authorization: Bearer $AGENT_TOKEN"

# Reply (any agent of the tenant may reply to any non-archived, non-resolved conversation)
curl -s -X POST "$A/conversations/<conversationId>/messages" -H "Authorization: Bearer $AGENT_TOKEN" \
  -H 'Content-Type: application/json' -d '{"clientMessageId":"…","body":"How can I help?"}'

# Lifecycle: resolve | reopen | pending | archive | unarchive  (all return the ConversationView)
curl -s -X POST "$A/conversations/<id>/resolve" -H "Authorization: Bearer $AGENT_TOKEN" \
  -H 'Content-Type: application/json' -d '{"expectedVersion":3,"reason":"answered"}'
curl -s -X POST "$A/conversations/<id>/reopen"  -H "Authorization: Bearer $AGENT_TOKEN" -d '{}' -H 'Content-Type: application/json'
curl -s -X POST "$A/conversations/<id>/pending" -H "Authorization: Bearer $AGENT_TOKEN" -d '{}' -H 'Content-Type: application/json'
curl -s -X POST "$A/conversations/<id>/archive" -H "Authorization: Bearer $AGENT_TOKEN" -H 'Content-Type: application/json' -d '{"reason":"spam"}'
curl -s -X POST "$A/conversations/<id>/unarchive" -H "Authorization: Bearer $AGENT_TOKEN" -d '{}' -H 'Content-Type: application/json'

# Assignment is Plan 04; until then the route is 501 NOT_IMPLEMENTED
curl -s -X POST "$A/conversations/<id>/assign" -H "Authorization: Bearer $AGENT_TOKEN" \
  -H 'Content-Type: application/json' -d '{"mode":"CLAIM"}'
```

Lifecycle rules in one line each: a command whose target already holds is a **200 no-op** (even with a stale
`expectedVersion`, except `reopen`/`pending` on an archived conversation, which are 409 `CONVERSATION_ARCHIVED`); a
mismatching `expectedVersion` is 409 `STALE_VERSION`; forbidden transitions are 409 `INVALID_TRANSITION`; an agent reply
into a resolved conversation is 409 `CONVERSATION_RESOLVED`; a contact message reopens a resolved or pending one
(`REOPENED_BY_CONTACT`); any message into an archived conversation is 409 `CONVERSATION_ARCHIVED` (start a new one).
Resolution and archiving release the agent's allocation capacity (`RELEASE_PENDING`), reopening or unarchiving an open
conversation requests a new assignment (`REOPEN_PENDING`).

`view=queue` lists waiting work (`REQUESTED`, `QUEUED`, `FAILED`, `REOPEN_PENDING`), `view=mine` the caller's
assignments, `view=all` everything; `archived=true` switches to the archive. The `cursor` is opaque: follow `nextCursor`
until it is `null`.

## 4. Problem codes

Every error is `application/problem+json` with a stable `code` and the trace id (`X-Trace-Id` header, `traceId`
property): `UNAUTHENTICATED` 401, `FORBIDDEN` 403, `NOT_FOUND` 404, `VALIDATION_FAILED` 400, `RATE_LIMITED` 429,
`CLIENT_MESSAGE_ID_REUSED` / `CONVERSATION_RESOLVED` / `CONVERSATION_ARCHIVED` / `INVALID_TRANSITION` / `STALE_VERSION` /
`CONVERSATION_ACTIVE_EXISTS` 409, `DATABASE_UNAVAILABLE` 503, `NOT_IMPLEMENTED` 501 (`/assign` today).

## 5. What is written to the database

One send transaction: lock the conversation row → participant check → idempotency lookup → lifecycle policy → next
`sequence` → message insert → activity/preview → participant upsert → sender's read cursor → state event (only for a
reopening) → outbox `message.created` (+ `conversation.updated` on a reopen). The outbox rows are only **written** in
Plan 02; the publisher that delivers them to Centrifugo arrives with Plan 03. `mvnw verify` runs the schema, write-path,
concurrency, history, read-cursor, lifecycle, tenancy and API integration tests against real PostgreSQL.

## 6. Seeded local values

| Setting | Default | Meaning |
|---|---|---|
| tenant | `tenant-local` | seeded tenant id (`APP_SEED_TENANT_ID`) |
| widget key | `wk_local_demo` | public inbox key (`APP_SEED_WIDGET_KEY`) |
| inbox | "Website" | allocation queue id `support` |
| agent | `agentbob` ("Agent Bob") | local demo agent (`LOCAL POC — NOT REAL AUTHENTICATION`) |
| contact | `userone` ("User One") | demo contact, reused by `externalId=userone` |
| retention | 30 days | `retention_policies` seed (purge arrives in Plan 05) |

New settings live in `.env.example`: `APP_SESSION_TOKEN_HMAC_SECRET_KEY` (`__generate__`), `APP_LOCAL_ADMIN_TOKEN`
(`__generate__`), `APP_LOCAL_DEV_ENABLED`, `APP_SEED_WIDGET_KEY`. The backend fails fast if the session secret is missing
or shorter than 32 characters, and refuses to start when `app.local-dev.enabled=true` in a production environment.
