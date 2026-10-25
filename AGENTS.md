# Agent rules (repository-wide)

1. Work from a spec: product features need `specs/features/<name>/feature.spec.md`. Do not invent product scope.
2. Contract first: update `libs/contracts/openapi/platform-api.yaml` with the implementation; keep
   `tests/contract` green.
3. Schema changes = new `database/flywayMigration/sql/V<n>__*.sql`. Never edit an applied migration. Never add
   Flyway/Liquibase to the runtime app. The app role is DML-only.
4. Backend: one module per top-level package under `com.byteflock.chatplatform`; `ModularityTests` must pass. No
   `utils` dumping ground; `shared` is for minimal cross-cutting config only.
5. Frontend: React code and Lit code never import each other; share only design tokens. Components use semantic
   tokens (`--cp-color-*`, `--cp-spacing-*`), never hard-coded colours.
6. Config via environment variables. Browser-facing URLs must be host-reachable (`localhost`), never Compose names.
7. Never commit `.env` or secrets; add new settings to `.env.example` with placeholders and to
   `scripts/dev-setup.ps1` if they need generated values.
8. Pin versions (no `latest`); record changes in `docs/setup/tool-versions.md`.
9. Never run `docker compose down -v`, `git reset --hard` or delete volumes unless the user asks.
10. Before handing over run `pnpm verify` (or the subset you can) and report PASS/FAIL/BLOCKED honestly with
    the commands you ran.

Commands: `pnpm lint | typecheck | test | build | contracts:validate | e2e | verify`, `pnpm db:migrate`,
`pnpm dev:backend | dev:dashboard | dev:widget`, `docker compose up -d --wait`. Details: `README.md`.
