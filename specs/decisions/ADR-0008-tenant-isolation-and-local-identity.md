# ADR-0008: Tenant isolation by token and query scoping; local identity shortcuts

- **Status:** Accepted (design); validation pending in Plans 02 and 03 (API and token isolation tests) and Plan 07
  (`PlatformRaceIT.tenantIsolationUnderConcurrentLoad`, `Invariants.assertAll`). The local identity part is explicitly
  **not** production authentication.
- **Date:** 2026-10-27
- **Master spec name:** ADR-004 "tenant isolation"
- **Details:** [tenant-security.md](../../docs/architecture/tenant-security.md)

## Context

**Existing:** there is no tenant anywhere (no `tenant_id` column, claim or channel segment) and no authentication: the
demo caller names itself with `actorId` (`userone` or `agentbob`), labelled "LOCAL POC — NOT REAL AUTHENTICATION"
(`chat/DemoActor.java`; workspace-inventory.md sections 4.8, 8.2). Master spec 7 makes tenant isolation a core
requirement: tenant and identity come from verified credentials, every query is scoped, subscriptions are authorized
per channel, and development bypasses must be off by default and fail startup in production. None of the reference
systems enforces tenancy in the database ([reference-comparison.md](../../docs/research/reference-comparison.md) row 10).
Overview §4 allows no new infrastructure, so an identity provider container is out of scope for the MVP.

## Options

Isolation:
1. **Application-level scoping**: the tenant comes from a verified token; every SQL statement on a tenant-owned table
   filters `tenant_id`; tests per endpoint and per token request.
2. **PostgreSQL row-level security** with a per-transaction tenant setting (`SET LOCAL`), in addition to option 1.
3. **Schema or database per tenant.**
4. **Composite `(tenant_id, id)` foreign keys** so cross-tenant references cannot be stored.

Identity:
- (a) **One HS256 session JWT for both principals** (`typ = contact | agent`), local agent login and self-declared widget
  identity as local-dev shortcuts, an admin token for operator endpoints.
- (b) **OIDC now** (an identity provider container plus HMAC-verified widget identity): production-grade, but new
  infrastructure and not needed to prove the message lifecycle.
- (c) Keep the demo `actorId`: not acceptable even locally once tenants exist.

## Decision

Option 1 with identity (a) (overview D3, D7, D14; rulings R6, R10):

- Session JWT claims `sub`, `tenant`, `typ`, `inbox` (contacts), `iat`, `exp`; TTL 24 h for contacts, 12 h for agents;
  secret `APP_SESSION_TOKEN_HMAC_SECRET_KEY`, distinct from the Centrifugo secrets. Visitors get a token from
  `POST /api/v1/widget/sessions` (public `widgetKey` → inbox → contact); agents from `POST /api/v1/agent/local-login`
  (local-dev only).
- Every tenant-owned table carries `tenant_id`, every statement filters it; the only cross-tenant statements are the
  outbox and assignment worker claims, which carry each row's tenant into every follow-up. A contact reaches only its
  own conversations; an agent reaches every conversation of its tenant's inboxes. Cross-tenant access is 403 at the API
  and at subscription-token issuance; an unknown id is 404.
- Centrifugo subscription tokens are issued per exact channel after the tenant and participant check;
  `chat:conversation:<uuid>` has no tenant segment, `chat:inbox:<tenantId>:<inboxUuid>` has one.
- Admin endpoints need `X-Admin-Token` and exist only when `app.local-dev.enabled=true`; `LocalDevGuard` fails startup
  when that flag is on with `app.environment=production`.
- No row-level security and no composite foreign keys in the MVP (R10).
- Every local shortcut keeps the "LOCAL POC — NOT REAL AUTHENTICATION" label; their production replacements are listed
  in tenant-security.md section 9 (OIDC for agents, HMAC-verified widget identity, per-tenant widget origins, operator
  authentication, secret rotation, a shared rate-limit store).

## Consequences

- Isolation rests on discipline and tests: a missed predicate is a leak until a test finds it. Plan 02's global
  constraint treats any repository method without a `tenantId` parameter as a defect, Plans 02 to 07 add a
  cross-tenant test per endpoint family and per token request, and Plan 07 asserts cross-table tenant consistency with
  SQL invariants.
- The 403-versus-404 rule needs one id-only existence probe that returns no row data; it reveals that an id exists,
  which is acceptable for random UUIDs.
- Visitor identity is weak by design: anyone can claim an `externalId` and resume that visitor's conversation. This is
  acceptable only locally.
- Changing a session secret ends every session (no key ids, no rotation).

## Operational impact

- Secrets in `.env` (`APP_SESSION_TOKEN_HMAC_SECRET_KEY`, `APP_LOCAL_ADMIN_TOKEN`, `__generate__` placeholders in
  `.env.example`); never in `PUBLIC_*`/`VITE_*` variables or logs.
- `app.local-dev.enabled` defaults to `false`; the `local` profile turns it on. Deployments outside a developer machine
  must keep it off; the guard enforces this for `app.environment=production`.
- Rate limits are per instance (D17); behind a load balancer with several instances they multiply.

## Evidence that would trigger a change

- **RLS trigger (R10):** a second service, a reporting tool or ad-hoc SQL access gets credentials to the platform
  database; or any cross-tenant defect is found in review or test. Then add row-level security with `SET LOCAL` per
  transaction (pool-safe) and composite `(tenant_id, id)` foreign keys.
- Any deployment beyond a developer machine (shared dev, UAT, production): replace the local identity shortcuts as
  listed in tenant-security.md section 9 before it happens.
- More than one backend instance: a shared rate-limit store.
- A tenant needs data residency or per-tenant encryption: re-evaluate option 3.
