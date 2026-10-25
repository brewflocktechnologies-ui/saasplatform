# Contributing

## Branches and commits

- Branch from `main`: `feat/<spec-name>`, `fix/<topic>`, `chore/<topic>`, `docs/<topic>`.
- Conventional commits: `feat(widget): ...`, `fix(backend): ...`, `chore(deps): ...`.
- Keep spec, contract, migration and implementation of one feature in the same PR when practical.

## Pull requests

A PR must:

1. Reference its feature spec or ADR (or explain why none is needed).
2. Pass CI (`.github/workflows/ci.yml`): lint, typecheck, tests, builds, Maven `verify`, contract lint, Compose
   config, E2E.
3. Pass `pnpm verify` locally when it touches runtime behaviour (paste the summary).
4. Include contract/migration changes with backward-compatibility notes.
5. Not contain secrets, `.env`, build output or edited applied migrations.

## Review checklist

- Module boundaries (Nx tags, Spring Modulith) respected; no new cross-app imports.
- Tenant isolation and authorization considered (once tenancy exists).
- UI: loading/error states, keyboard access, semantic tokens.
- Telemetry: new endpoints are visible in metrics/traces; logs contain no secrets or personal data.
- Docs updated (README, local-development, runbooks, ADRs) when behaviour or commands change.
