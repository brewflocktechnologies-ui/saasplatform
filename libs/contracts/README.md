# contracts

API-first contracts for the platform.

| Path | Purpose |
|---|---|
| `openapi/platform-api.yaml` | OpenAPI 3.1 contract of `modulithplatformservice` (`/api/v1/status`, `/api/v1/bootstrap`, RFC 9457 errors) |
| `asyncapi/chat-realtime.yaml` | AsyncAPI 3.0: Centrifugo channels `chat:inbox`, `chat:conversation:{id}` and their events |
| `json-schema/chat/` | message, conversation, `message.created`, `conversation.created` (JSON Schema 2020-12, referenced by OpenAPI and AsyncAPI) |
| `redocly.yaml` | lint rules |

- Validate: `pnpm contracts:validate` = Redocly lint of OpenAPI + AsyncAPI, then `tools/validate-json-schemas.mjs`
  (strict Ajv compile of every schema + its examples).
- Conformance: `tests/contract/platform-api.spec.ts` validates **live** responses against the component schemas
  with Ajv (JSON Schema 2020-12), runs as part of `pnpm e2e`.
- No client is generated yet: the dashboard uses two hand-written types mirroring the schema. Add generation
  (e.g. `openapi-typescript`) once the API grows beyond a handful of endpoints.
- Event conformance: `chat-client` unit tests and the live Centrifugo test in `tests/contract` validate events
  against `json-schema/chat`.
