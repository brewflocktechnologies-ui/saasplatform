import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from '@playwright/test';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { parse } from 'yaml';
import { urls } from '../playwright.config';

/** Validates live backend responses against libs/contracts/openapi/platform-api.yaml (OpenAPI 3.1 = JSON Schema 2020-12). */
const spec = parse(
  readFileSync(join(__dirname, '..', '..', 'libs', 'contracts', 'openapi', 'platform-api.yaml'), 'utf8'),
);
const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
ajv.addSchema({ $id: 'platform-api', components: spec.components });
function expectValid(schema: string, body: unknown) {
  const validate = ajv.getSchema(`platform-api#/components/schemas/${schema}`);
  if (!validate) throw new Error(`Schema ${schema} not found in platform-api.yaml`);
  expect(validate(body), JSON.stringify(validate.errors)).toBe(true);
}

test.describe('platform-api contract (live backend)', () => {
  test('GET /api/v1/status conforms to StatusResponse', async ({ request }) => {
    const res = await request.get(`${urls.api}/api/v1/status`);
    expect(res.status()).toBe(200);
    expect(res.headers()['content-type']).toContain('application/json');
    expectValid('StatusResponse', await res.json());
  });

  test('GET /api/v1/bootstrap conforms to BootstrapRecord', async ({ request }) => {
    const res = await request.get(`${urls.api}/api/v1/bootstrap`);
    expect(res.status()).toBe(200);
    const body = await res.json();
    expectValid('BootstrapRecord', body);
    expect(body.setupKey).toBe('local-poc');
  });

  test('errors use application/problem+json without stack traces', async ({ request }) => {
    const res = await request.get(`${urls.api}/api/v1/does-not-exist`);
    expect(res.status()).toBe(404);
    expect(res.headers()['content-type']).toContain('application/problem+json');
    const body = await res.json();
    expectValid('Problem', body);
    expect(JSON.stringify(body)).not.toMatch(/trace|exception/i);
  });

  test('every documented path is implemented', async ({ request }) => {
    for (const path of Object.keys(spec.paths)) {
      const res = await request.get(`${urls.api}${path}`);
      expect(res.status(), path).toBeLessThan(500);
    }
  });

  test('actuator health is UP', async ({ request }) => {
    const res = await request.get(`${urls.api}/actuator/health`);
    expect(res.status()).toBe(200);
    expect((await res.json()).status).toBe('UP');
  });
});
