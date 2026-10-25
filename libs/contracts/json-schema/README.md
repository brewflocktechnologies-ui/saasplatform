# JSON Schema (message and event contracts)

Empty on purpose: no asynchronous messages or events exist yet, and inventing them now would fix the product domain
prematurely.

When a feature spec introduces a message/event (e.g. a Centrifugo publication or a chat message payload):

1. Add `<domain>/<name>.v<major>.schema.json` using JSON Schema **2020-12** with `$id`
   `https://schemas.chat-platform.local/<domain>/<name>.v<major>.json`, `title`, `description` and `examples`.
2. Versioning: additive optional fields → same major; removing/renaming/retyping fields → new major file, keep the
   old one until all producers/consumers migrate. Payloads carry `schemaVersion`.
3. Describe channels and which schema each carries in an AsyncAPI 3 document (`libs/contracts/asyncapi/`).
4. Validate in tests (Ajv 2020, as in `tests/contract`) on both producer and consumer side, and add the schema
   folder to `contracts:validate`.
5. Tenant identifiers and authorization rules belong in the spec's threat section; never put secrets in payloads.
