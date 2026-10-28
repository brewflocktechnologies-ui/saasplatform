# Conversation platform: tenant security

Design document (master spec 7, 11.3, 16). Plan 02 T2/T4 implements sessions, caller resolution and API checks,
Plan 03 T3/T4 channel authorization and typing, Plan 05 T2/T4 the admin API and the readiness checklist (which Plan 05
appends to this file). Conventions (Existing, `D<n>`, `R<n>`): [system-overview.md](system-overview.md) section 0.
Decision record: [ADR-0008](../../specs/decisions/ADR-0008-tenant-isolation-and-local-identity.md).

> **LOCAL POC — NOT REAL AUTHENTICATION.** Agent local login, the widget's self-declared visitor identity and the shared
> admin token are local-development shortcuts. Section 9 lists each one and what replaces it before any shared
> environment.

**Existing (replaced):** the demo has no authentication: the caller names itself with `actorId` (`userone` or
`agentbob`, `chat/DemoActor.java`), there is no tenant anywhere, and any client that reaches port 8080 can read every
conversation and mint tokens for any channel ([workspace-inventory.md](../research/workspace-inventory.md) section 4.8).
**Existing (kept):** Centrifugo connection and subscription JWTs are HS256 with two different secrets of at least 32
characters and a 5-minute TTL, signed by `realtime/RealtimeTokenIssuer.java`; the namespace denies client subscribe and
publish without a token (`infra/centrifugo/config.json`).

## 1. Principals and session tokens (D7)

| Principal (`PrincipalType`) | How it is obtained | Credential |
|---|---|---|
| `CONTACT` (visitor) | `POST /api/v1/widget/sessions` `{widgetKey, externalId?, displayName?, sessionToken?}` (public) | bearer session JWT, `typ = contact` |
| `AGENT` | `POST /api/v1/agent/local-login` `{agentId, tenantId?}` (local-dev only) | bearer session JWT, `typ = agent` |
| `ADMIN` (operator) | configuration | header `X-Admin-Token` = `APP_LOCAL_ADMIN_TOKEN` (local-dev only) |

Session JWT (`tenancy.SessionTokens`), HS256 signed with `APP_SESSION_TOKEN_HMAC_SECRET_KEY` (`app.sessions.secret`;
startup fails if it is missing or too short, naming the variable without printing it):

| Claim | Contact | Agent |
|---|---|---|
| `sub` | contact id (UUID) | agent id |
| `tenant` | tenant id | tenant id |
| `typ` | `contact` | `agent` |
| `inbox` | inbox id (UUID) | absent |
| `iat`, `exp` | issue time; `exp = iat + 24h` (`app.sessions.contact-ttl`) | `exp = iat + 12h` (`app.sessions.agent-ttl`) |
| `auth_time` | first issue time of the contact's session: set when a session starts, copied unchanged into every refreshed token (R-P31 M4) | absent |

- `exp` is checked against the injected `Clock`. A token signed with a Centrifugo secret, with an unknown `typ` or with
  a bad signature is rejected (`SessionTokensTest`).
- `verifyForRefresh` accepts an expired `contact` token with a valid signature as long as its `auth_time` is at most
  `app.sessions.contact-refresh-max-age` (30 days) ago; only `POST /widget/sessions` uses it, so a returning visitor
  keeps its contact for up to 30 days after the first issue. An older token is rejected with 401 `UNAUTHENTICATED`, and
  the widget then starts a new session (`TenancyIT.refreshBeyondMaxAgeIsRejected`; R-P31 M4). A stolen contact token
  therefore cannot be refreshed for ever. An expired agent token is never refreshed.
- The verified token becomes `Caller(tenantId, type, principalId, inboxId)`; `AuthInterceptor` acts only on handler
  methods annotated `@RequiresPrincipal`, puts `tenantId` in the logging MDC, and lets CORS pre-flight `OPTIONS` and the
  two public endpoints pass.
- Errors: no or invalid bearer → 401 `UNAUTHENTICATED`; valid token of the wrong principal type → 403 `FORBIDDEN`.
- Tokens travel in the `Authorization` header, never in a URL or cookie, so CSRF does not apply; the widget keeps its
  token in `localStorage` (`cp.session.<widgetKey>`), the dashboard in `sessionStorage` (`cp.agent.session`), which makes
  XSS the main token threat (section 8).

Centrifugo tokens (`GET /api/v1/{widget|agent}/realtime/connection-token` and `…/subscription-token?channel=`): the
connection token's `sub` is `<tenantId>:<contact|agent>:<principalId>`; the subscription token adds the exact
`channel` claim; TTL `CENTRIFUGO_TOKEN_TTL` (5 minutes). A subscription token is issued only after section 5's check.
Counter `realtime.tokens{type=connection|subscription, outcome=issued|denied}` makes denials visible (master spec 15).

## 2. Where the tenant comes from

| Endpoint family | Authentication | Tenant source | Other scope |
|---|---|---|---|
| `POST /api/v1/widget/sessions` | none (public) | `widgetKey` → inbox → tenant; a refreshed token must belong to the same tenant and inbox | contact: from the refreshed token, else by `externalId` (`ensureContact`), else a new anonymous contact |
| `/api/v1/widget/**` | bearer, `typ = contact` | `tenant` claim | `inbox` claim and `sub` (contact id) |
| `POST /api/v1/agent/local-login` | none; local-dev only | body `tenantId`, default `app.seed.tenant-id`; the agent must exist in `agents` for that tenant, else 404 | n/a |
| `/api/v1/agent/**` | bearer, `typ = agent` | `tenant` claim | `sub` (agent id); every inbox of the tenant |
| `/api/v1/admin/**` | `X-Admin-Token`; local-dev only | none in the caller (`Caller(null, ADMIN, "admin", null)`); the tenant is named in the path or body | cross-tenant operator |
| `GET /api/v1/status`, `/api/v1/bootstrap`, `/actuator/health` | none | none; return no tenant data | `/actuator/prometheus` only in the `local` profile (`ObservabilityIT.prometheusOnlyInLocalProfile`) |
| `OutboxPublisher`, `AssignmentWorker` | n/a | each claimed row's `tenant_id`, carried into every follow-up statement and call | the two claims are the only cross-tenant data statements; every other statement is tenant-filtered, except the authorization probe and the statements keyed only by primary key inside a transaction after a tenant-filtered lock or lookup (including the outbox marks by `event_id` + worker id after a claim), as stated in section 4 (R-P6 amended) |
| Calls to the allocation service | `X-Tenant-Id` = our tenant id, `X-Role` = `operator` (`admin` for seeding) | the conversation's or agent's tenant | [allocation-integration.md](allocation-integration.md) |

Request bodies never carry a tenant id that the server trusts, except the admin and local-login endpoints above.

## 3. Participant rules (D14)

| Action | Contact | Agent of the tenant |
|---|---|---|
| Read a conversation, its history and its view | only its own: `contact_id = sub`, `inbox_id = inbox` claim, same tenant; the view's `assignment` is `ContactAssignmentView {state, agentId}` (R-P3, R-P29) | every conversation of every inbox of its tenant, with the full assignment |
| Send a message | own conversation; lifecycle rules apply | any non-archived conversation of its tenant (an agent message into `RESOLVED` is 409); sending adds an `AGENT` participant row. Assignment routes work but does not gate sending. |
| Lifecycle commands, `/assign` | none | all ([domain-model.md](domain-model.md) section 4) |
| Typing, read cursor | own conversation | any conversation of its tenant |
| List inboxes and conversations | none | its tenant's inboxes |
| Presence | none | its own (`PUT /api/v1/agent/presence`, `GET /api/v1/agent/me`) |

Cross-tenant access is 403 at the API and at token issuance (D14).

## 4. 403 versus 404

- 404 `NOT_FOUND`: no conversation (or inbox) with that id exists. For an inbox, existence is checked inside the
  caller's tenant only: an agent of tenant A asking for `chat:inbox:<A>:<id of an inbox of tenant B>` gets 404, exactly
  as for an unknown id, because the tenant-filtered lookup in A finds nothing (R-P18, R-P22; section 5).
- 403 `FORBIDDEN`: it exists but belongs to another tenant, another contact or another inbox of the contact's tenant;
  for inbox channels, the channel names a tenant other than the caller's (decided from the name, no lookup, R-P18).
- Every data statement is tenant-filtered (D3), with two allowed shapes besides (R-P6 amended):
  - **one authorization probe**, `ConversationRepository.tenantAndInboxOf(UUID id)`, the only statement on a
    tenant-owned table without a tenant filter. It returns only `(tenantId, inboxId, contactId)`, never conversation
    data, and is used only to answer 403 (exists, other scope) versus 404 (no such id);
  - **statements keyed only by primary key inside a transaction, after a tenant-filtered lock or lookup of that row**
    (for example `bumpSequence`, the guarded `UPDATE … WHERE id = :id AND version = :expected`) and the worker marks
    keyed by `event_id` and worker id after a claim.
- The 403 confirms to a caller of another tenant that the id exists; ids are random UUID v7 values that cannot be
  enumerated, so the risk is low ([reference-comparison.md](../research/reference-comparison.md) row 10).

Tests: `ConversationApiIT.contactCannotReadAnotherInboxConversation`, `sameAgentIdInTwoTenantsIsIsolated`,
`unknownConversationIs404ForParticipantsAnd403ForOthers`; `MessageWriteIT.nonParticipantContactIs403`,
`crossTenantAgentIs403`; `HistoryIT.historyIsTenantScoped`.

## 5. Channel authorization (`ChannelAuthorizer`, D14, R6, R-P18)

| Requested channel | Contact | Agent |
|---|---|---|
| `chat:conversation:<id>` of its own conversation | allowed | allowed (same tenant) |
| `chat:conversation:<id>` of another contact or inbox, same tenant | 403 | allowed |
| `chat:conversation:<id>` of another tenant | 403 | 403 |
| `chat:inbox:<ownTenant>:<inboxOfOwnTenant>` | 403 | allowed |
| `chat:inbox:<otherTenant>:<any inbox id>` (the channel names a tenant other than the caller's) | 403 | 403, from the channel name alone, no lookup (R-P18) |
| `chat:inbox:<ownTenant>:<unknown id, or an inbox of another tenant>` | 403 | 404 from the tenant-filtered inbox lookup; an unknown id and another tenant's inbox answer alike, so no existence is confirmed (R-P18, R-P22) |
| `chat:conversation:<id>` that does not exist | 404 (decided with the R-P6 probe) | 404 |
| malformed (not exactly one of the two forms, uppercase UUID, extra segments) | 400 `VALIDATION_FAILED` | 400 |

The browser cannot subscribe without a token (namespace option), cannot reuse a token for another channel (Centrifugo
checks the `channel` claim; code 103), and cannot publish (client publish denied). Inbox channels need no probe: the channel name carries the tenant, and a CONTACT
caller gets 403 for every inbox channel (R-P18). Durable events reach Centrifugo only
from the backend with `CENTRIFUGO_HTTP_API_KEY`, which never reaches a browser. Tests: `RealtimeTokenIT` (every
case above), contract `subscriptionTokenForAnotherChannelIsRejected`, E2E `nonParticipantCannotLoadHistoryOrSubscribe`.

## 6. Admin endpoints and the production guard

- Routes (overview §5): `GET /api/v1/admin/outbox`, `POST /api/v1/admin/outbox/{eventId}/replay`,
  `POST /api/v1/admin/retention/purge`, `GET /api/v1/admin/retention/runs/{id}`,
  `PUT /api/v1/admin/retention/policies/{tenantId}`, `POST /api/v1/admin/tenants`.
- The controllers are `@ConditionalOnProperty(app.local-dev.enabled)`: with the flag off the routes do not exist (404).
  They require `X-Admin-Token` equal to `app.local-dev.admin-token` (`APP_LOCAL_ADMIN_TOKEN`), compared in constant
  time with `MessageDigest.isEqual` (R-P11); a blank configured token rejects every admin request. A session JWT is not accepted on admin routes and the
  admin token is not accepted on widget or agent routes (`AdminApiIT.principalTypesDoNotCross`).
- `app.local-dev.enabled` defaults to `false` and is `true` only in the `local` profile.
- **Production guard:** `LocalDevGuard` fails startup with "app.local-dev.enabled must be false when
  app.environment=production" if both are set (`LocalDevGuardTest`, master spec 7.3). The same flag gates local login,
  the admin API and the purge `cutoff` override.
- Admin actions are audited by their own records: purge runs in `purge_runs`, outbox replays logged at INFO with
  `eventId` (R16).

## 7. Rate limits (D17) and origins

| Limit | Key | Default | Config | Error |
|---|---|---|---|---|
| Widget sessions | widget key + client IP | 10 per minute (the `local` profile sets 600 for the E2E and contract suites, overview §7 G) | `app.rate-limits.sessions-per-minute` (`APP_RATE_LIMIT_SESSIONS_PER_MINUTE`) | 429 `RATE_LIMITED` |
| Messages | `msg:<tenantId>:<principalType>:<principalId>`: the tenant and type keep the same agent id in two tenants apart (R-P31 M3) | 30 per minute | `app.rate-limits.messages-per-minute` (`APP_RATE_LIMIT_MESSAGES_PER_MINUTE`) | 429 |
| Typing | principal + conversation | 1 per 2 s | `app.rate-limits.typing-interval` | 429 |

`tenancy.RateLimiter` holds in-memory token buckets per backend instance (a `ponytail:` ceiling: with more than one
instance the limits multiply and need a shared store). Counter `rate_limit.rejections{scope}`. Uploads have no limit
because there are no uploads (D15).

Origins: **existing** backend CORS allows `APP_CORS_ALLOWED_ORIGINS` (`http://localhost:3000,http://localhost:5173`,
`shared/WebConfig.java`); Plan 02 T4 adds methods `PUT`, `DELETE`, `OPTIONS` and headers `Authorization`,
`Content-Type`, `Idempotency-Key`, `X-Admin-Token`, and keeps exposing `X-Trace-Id`. Centrifugo checks the same two
origins in `client.allowed_origins` (a static list). **Existing:** the widget bundle is served with
`Access-Control-Allow-Origin: *` (`apps/chatwidget/nginx.conf`) so it can be embedded; it holds no secrets. Browser-facing URLs are `localhost` URLs only, and no secret is in a
`PUBLIC_*` or `VITE_*` variable (overview §4).

## 8. Threats and mitigations

| Threat | Mitigation | Evidence |
|---|---|---|
| Spoofing another visitor or agent | identity only from signed session tokens; local shortcuts confined to local-dev (section 9) | `SessionTokensTest`, `TenancyIT` |
| Cross-tenant read or write | tenant from the token; tenant predicate in every statement; 403 at API and token issuance | section 4 tests; `PlatformRaceIT.tenantIsolationUnderConcurrentLoad`; `Invariants.assertAll` |
| Subscribing to another conversation or tenant's inbox | per-channel subscription tokens after `ChannelAuthorizer` | section 5 tests |
| Forging durable events or typing for another tenant | client publish denied; typing through the authorized API only | `TypingIT.crossTenantIs403`, `nonParticipantIs403` |
| XSS through message bodies (token theft from storage) | bodies rendered as text nodes only (Lit text binding, React text child), never `innerHTML` | widget and dashboard `bodiesRenderAsText` (Plan 06) |
| Abuse and flooding | rate limits (section 7); body limit 4000 code points | `ConversationApiIT.messageRateLimit`, `TenancyIT.widgetSessionRateLimited`, `TypingIT` |
| Leaking data in errors or logs | problem details with `code`, no stack, SQL, token or body; logs without bodies, tokens or secrets | `ConversationApiIT.validationErrorsAreProblemJsonWithCode`, `LogHygieneTest` |
| Enumerating ids | random UUID v7; 403/404 rule (section 4) | |
| Admin misuse | admin API absent unless local-dev; constant-time token check; startup guard in production | `AdminApiIT`, `OutboxAdminIT.adminRoutesAbsentWhenLocalDevDisabled`, `LocalDevGuardTest` |
| A visitor sees internal routing details | for contact callers and in every `conversation.created` / `conversation.updated` payload (the conversation channel is shared with the visitor), `assignment` is the two-field `ContactAssignmentView {state, agentId}` with both keys always present (`agentId` null when none); `decisionId`, `reason` and `explanation` are absent and reach agents only through `GET /api/v1/agent/conversations/{id}` (R-P3, R-P29) | `ConversationApiIT.contactViewHidesAssignmentInternals` (Plan 02), Plan 04 projection-event tests, `EventEnvelopeContractTest` (Plan 03) |

Isolation is enforced in application SQL and tests; the database adds no guard in the MVP (no row-level security, no
composite `(tenant_id, id)` foreign keys, R10). A missed predicate is therefore caught only by tests until the ADR-0008
trigger fires.

## 9. Local-only shortcuts and their production replacements

All of these are labelled **LOCAL POC — NOT REAL AUTHENTICATION** in the UI, the README and the code that implements
them.

| # | Local shortcut | Risk if shipped | Replacement before a shared environment |
|---|---|---|---|
| 1 | `POST /api/v1/agent/local-login`: anyone who can reach the API picks an agent id | full agent impersonation | OIDC (authorization code + PKCE) for agents; tenant membership and roles from the identity provider; the endpoint stays absent (local-dev flag off) |
| 2 | Widget identity is self-declared: `externalId` and `displayName` in `POST /widget/sessions` are trusted as sent | a visitor can claim another visitor's `externalId` and resume their conversation | HMAC-verified identity: the tenant's own server signs `externalId` with a per-inbox secret and the API verifies the signature before `ensureContact`; unsigned sessions stay anonymous ([chatwoot.md](../research/evidence/chatwoot.md) section 12 describes the same pattern) |
| 3 | `widgetKey` alone binds a browser to an inbox, from any origin | another site embeds the widget | per-tenant allowed widget origins, checked at session creation and mirrored in CORS and Centrifugo origin settings |
| 4 | One shared `X-Admin-Token` for every operator action | no per-operator accountability | operator authentication with an admin role through the same identity provider; admin API on an internal network only; per-operator audit |
| 5 | Seed identities (`tenant-local`, inbox key `wk_local_demo`, agent `agentbob`, contact `userone`) created at startup | known credentials | provisioning through an authenticated admin flow; `app.seed.enabled` off |
| 6 | HS256 shared secrets in `.env`, no rotation | a leaked secret signs any token | secrets from a secret store, key ids (`kid`) and rotation |
| 7 | In-memory, per-instance rate limits | limits multiply with instances | a shared limiter store when there is more than one instance (D17) |
| 8 | Purge `cutoff` override | an operator can purge recent data | accepted only with the local-dev flag; production uses the policy's retention days only |
