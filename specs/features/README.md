# Feature specifications

Every product feature starts as a spec in this folder. Code follows the spec, not the other way round.

## Workflow

1. `Copy-Item -Recurse specs/features/_template specs/features/<feature-name>` (kebab-case, e.g.
   `website-widget-configuration`).
2. Fill in `feature.spec.md`. Required sections:
   1. Problem and desired outcome.
   2. Actors, scope and explicit non-goals.
   3. User stories and acceptance criteria (testable, numbered).
   4. Data model and migration impact (new `V<n>__*.sql`, expand/contract plan).
   5. API/event contract changes (edit `libs/contracts/openapi/platform-api.yaml`; add JSON Schema/AsyncAPI only
      when the feature introduces events).
   6. Tenant authorization and threat considerations.
   7. UI states (loading/empty/error/success) and accessibility.
   8. Test plan and observable success criteria (metrics/logs/traces to check).
   9. Rollout, compatibility and rollback plan.
   10. ADR references and handover checklist.
3. Review the spec (PR with only the spec is fine).
4. Ask the agent: *"Implement `specs/features/<feature-name>/feature.spec.md`"*. The agent follows `AGENTS.md`:
   contract first, migration, backend module, UI, tests, `pnpm verify`.
5. Record significant decisions as ADRs in `specs/decisions/`.

## Where things go

| Change | Location |
|---|---|
| Backend module | `services/modulithplatformservice/src/main/java/com/byteflock/chatplatform/<module>/` |
| Migration | `database/flywayMigration/sql/V<n>__<description>.sql` |
| HTTP contract | `libs/contracts/openapi/platform-api.yaml` |
| Event/message schema | `libs/contracts/json-schema/` (+ AsyncAPI document when events are real) |
| Dashboard UI | `apps/chatdashboardapp/src/app/...` |
| Widget UI | `apps/chatwidget/src/...` |
| Reusable UI | `libs/componentLibrary/react` or `libs/componentLibrary/web` (never both from one component) |
| E2E / contract tests | `tests/e2e`, `tests/contract` |
