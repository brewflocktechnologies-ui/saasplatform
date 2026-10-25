// Compiles every libs/contracts/json-schema/**/*.schema.json with Ajv (JSON Schema 2020-12, strict) and checks each
// schema's own `examples`. Exits non-zero on the first problem.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const root = 'libs/contracts/json-schema';
const files = readdirSync(root, { recursive: true }).filter((f) => f.endsWith('.schema.json'));
const schemas = files.map((f) => JSON.parse(readFileSync(join(root, f), 'utf8')));

const ajv = new Ajv2020({ strict: true, allErrors: true });
addFormats(ajv);
for (const s of schemas) ajv.addSchema(s);

for (const s of schemas) {
  const validate = ajv.getSchema(s.$id);
  for (const example of s.examples ?? []) {
    if (!validate(example)) {
      console.error(`${s.$id}: example invalid`, validate.errors);
      process.exit(1);
    }
  }
}
console.log(`OK ${schemas.length} JSON schemas compiled (strict) and examples valid`);
