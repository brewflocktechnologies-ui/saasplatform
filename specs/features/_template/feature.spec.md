# Feature: <name>

- **Status:** Draft | In review | Approved | Implemented
- **Owner:** <name>
- **Date:** <yyyy-mm-dd>
- **Related ADRs:** <links or "none">

## 1. Problem and desired outcome

<Who has which problem today, and what will be true when this ships.>

## 2. Actors, scope and non-goals

- Actors: <operator, visitor, tenant admin, system ...>
- In scope: <...>
- Non-goals: <explicitly excluded>

## 3. User stories and acceptance criteria

- **US-1** As a <actor> I want <capability> so that <value>.
  - AC-1.1 Given <state> when <action> then <observable result>.
  - AC-1.2 ...

## 4. Data model and migration impact

- New/changed tables and columns (with tenant key):
- Migration file(s): `V<n>__<description>.sql`
- Backward compatibility with the running app version (expand/contract):
- Data backfill / retention:

## 5. API / event contract changes

- OpenAPI changes (paths, schemas, error cases) in `libs/contracts/openapi/platform-api.yaml`:
- Events (only if real async events are introduced): JSON Schema / AsyncAPI:
- Versioning / deprecation:

## 6. Tenant authorization and threat considerations

- Who may call what, and how tenant isolation is enforced (query scoping, tokens, Centrifugo channels):
- Threats (spoofing, data leakage across tenants, injection, abuse/rate limits) and mitigations:
- Secrets/config introduced:

## 7. UI states and accessibility

- States: loading / empty / error / success / partial:
- Keyboard and screen-reader behaviour, focus management, contrast (use semantic tokens):

## 8. Test plan and observable success criteria

- Unit: 
- Integration (Testcontainers): 
- Contract (`tests/contract`): 
- E2E (`tests/e2e`): 
- Telemetry: metrics/log lines/traces that prove it works in Grafana:

## 9. Rollout, compatibility and rollback

- Feature flag / staged rollout:
- Migration order and rollback (new forward migration, never edit applied ones):
- Client (widget embed API) compatibility:

## 10. ADRs and handover checklist

- [ ] Contract updated and `pnpm contracts:validate` passes
- [ ] Migration added; `pnpm db:validate` and `pnpm db:migrate` pass
- [ ] Module boundaries respected (`ModularityTests`, `pnpm lint`)
- [ ] Tests added; `pnpm verify` passes
- [ ] Docs/runbooks updated; ADR written if a significant decision was made
