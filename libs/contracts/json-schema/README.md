# JSON Schema (message and event contracts)

Current schemas: `chat/` (chat MVP: message, conversation, message.created, conversation.created), documented in
`../asyncapi/chat-realtime.yaml` and referenced from the OpenAPI contract.

When a feature spec introduces a message/event (e.g. a Centrifugo publication or a chat message payload):

1. Add `<domain>/<name>.v<major>.schema.json` using JSON Schema **2020-12** with `$id` equal to the file name under
   `https://schemas.chat-platform.local/<domain>/` (so relative `$ref`s resolve the same in Redocly and Ajv), plus `title`, `description` and `examples`.
2. Versioning: additive optional fields → same major; removing/renaming/retyping fields → new major file, keep the
   old one until all producers/consumers migrate. Payloads carry `schemaVersion`.
3. Describe channels and which schema each carries in an AsyncAPI 3 document (`libs/contracts/asyncapi/`).
4. Validate in tests (Ajv 2020, as in `tests/contract`) on both producer and consumer side, and add the schema
   folder to `contracts:validate`.
5. Tenant identifiers and authorization rules belong in the spec's threat section; never put secrets in payloads.
