# contracts

API-first contracts for the platform.

| Path | Purpose |
|---|---|
| `openapi/platform-api.yaml` | OpenAPI 3.1 contract of `modulithplatformservice` (`/api/v1/status`, `/api/v1/bootstrap`, RFC 9457 errors) |
| `json-schema/` | future message/event schemas (see its README) |
| `redocly.yaml` | lint rules |

- Lint: `pnpm contracts:validate` (Redocly CLI, `recommended` ruleset; disabled rules are justified in `redocly.yaml`).
- Conformance: `tests/contract/platform-api.spec.ts` validates **live** responses against the component schemas
  with Ajv (JSON Schema 2020-12), runs as part of `pnpm e2e`.
- No client is generated yet: the dashboard uses two hand-written types mirroring the schema. Add generation
  (e.g. `openapi-typescript`) once the API grows beyond a handful of endpoints.
- AsyncAPI is added with the first feature that introduces real asynchronous business events.
