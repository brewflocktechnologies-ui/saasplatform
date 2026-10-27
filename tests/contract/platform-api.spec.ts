import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type APIRequestContext } from '@playwright/test';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { Centrifuge, type PublicationContext } from 'centrifuge';
import { parse } from 'yaml';
import { urls } from '../playwright.config';

/**
 * Validates live backend responses against libs/contracts/openapi/platform-api.yaml (OpenAPI 3.1 = JSON Schema
 * 2020-12) and live Centrifugo events against libs/contracts/json-schema.
 */
const contracts = join(__dirname, '..', '..', 'libs', 'contracts');
const SCHEMA_BASE = 'https://schemas.chat-platform.local/';

// OpenAPI references the event/DTO schemas by relative file path; point those refs at the schemas' $id instead.
const rebase = (node: unknown): unknown =>
  Array.isArray(node)
    ? node.map(rebase)
    : node && typeof node === 'object'
      ? Object.fromEntries(
          Object.entries(node).map(([k, v]) =>
            k === '$ref' && typeof v === 'string' && v.startsWith('../json-schema/')
              ? [k, SCHEMA_BASE + v.slice('../json-schema/'.length)]
              : [k, rebase(v)],
          ),
        )
      : node;

const spec = parse(readFileSync(join(contracts, 'openapi', 'platform-api.yaml'), 'utf8'));
const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
for (const f of readdirSync(join(contracts, 'json-schema', 'chat'))) {
  ajv.addSchema(JSON.parse(readFileSync(join(contracts, 'json-schema', 'chat', f), 'utf8')));
}
ajv.addSchema({ $id: 'platform-api', components: rebase(spec.components) });

function expectValid(schema: string, body: unknown) {
  const validate = schema.startsWith('https://')
    ? ajv.getSchema(schema)
    : ajv.getSchema(`platform-api#/components/schemas/${schema}`);
  if (!validate) throw new Error(`Schema ${schema} not found`);
  expect(validate(body), JSON.stringify(validate.errors)).toBe(true);
}

async function expectProblem(res: Awaited<ReturnType<APIRequestContext['get']>>, status: number) {
  expect(res.status()).toBe(status);
  expect(res.headers()['content-type']).toContain('application/problem+json');
  const body = await res.json();
  expectValid('Problem', body);
  expect(JSON.stringify(body)).not.toMatch(/stack ?trace|exception|sql|secret/i);
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
    await expectProblem(await request.get(`${urls.api}/api/v1/does-not-exist`), 404);
  });

  test('actuator health is UP', async ({ request }) => {
    const res = await request.get(`${urls.api}/actuator/health`);
    expect(res.status()).toBe(200);
    expect((await res.json()).status).toBe('UP');
  });
});

test.describe('demo chat API contract (live backend)', () => {
  test('conversation get-or-create is idempotent and visible in the agent inbox', async ({ request }) => {
    const first = await request.post(`${urls.api}/api/v1/demo/conversations`);
    expect([200, 201]).toContain(first.status());
    const conversation = await first.json();
    expectValid('Conversation', conversation);
    expect(conversation).toMatchObject({ visitorId: 'userone', assignedAgentId: 'agentbob', status: 'OPEN' });

    const second = await request.post(`${urls.api}/api/v1/demo/conversations`);
    expect(second.status()).toBe(200);
    expect((await second.json()).id).toBe(conversation.id);

    const inbox = await request.get(`${urls.api}/api/v1/demo/conversations?actorId=agentbob`);
    expect(inbox.status()).toBe(200);
    const list = await inbox.json();
    list.forEach((c: unknown) => expectValid('Conversation', c));
    expect(list.map((c: { id: string }) => c.id)).toContain(conversation.id);
  });

  test('send + history conform to the contract; invalid input is rejected', async ({ request }) => {
    const { id } = await (await request.post(`${urls.api}/api/v1/demo/conversations`)).json();
    const url = `${urls.api}/api/v1/demo/conversations/${id}/messages`;

    const sent = await request.post(url, { data: { actorId: 'userone', text: `contract-${Date.now()}` } });
    expect(sent.status()).toBe(201);
    const body = await sent.json();
    expectValid('SendMessageResponse', body);
    expect(body.message.senderRole).toBe('VISITOR');

    const history = await request.get(`${url}?actorId=userone`);
    expect(history.status()).toBe(200);
    const messages = await history.json();
    messages.forEach((m: unknown) => expectValid('ChatMessage', m));
    const times = messages.map((m: { createdAt: string }) => Date.parse(m.createdAt));
    expect(times).toEqual([...times].sort((a, b) => a - b));

    await expectProblem(await request.post(url, { data: { actorId: 'userone', text: '   ' } }), 400);
    await expectProblem(await request.post(url, { data: { actorId: 'userone', text: 'x'.repeat(4001) } }), 400);
    await expectProblem(await request.post(url, { data: { actorId: 'mallory', text: 'hi' } }), 400);
    await expectProblem(await request.get(`${urls.api}/api/v1/demo/conversations?actorId=userone`), 403);
    await expectProblem(
      await request.post(`${urls.api}/api/v1/demo/conversations/00000000-0000-7000-8000-000000000000/messages`, {
        data: { actorId: 'userone', text: 'hi' },
      }),
      404,
    );
  });

  test('realtime tokens are issued only for allowed actor/channel pairs', async ({ request }) => {
    const { id } = await (await request.post(`${urls.api}/api/v1/demo/conversations`)).json();
    const token = (actor: string, channel: string) =>
      request.get(`${urls.api}/api/v1/demo/realtime/subscription-token`, { params: { actorId: actor, channel } });

    const connection = await request.get(`${urls.api}/api/v1/demo/realtime/connection-token?actorId=userone`);
    expect(connection.status()).toBe(200);
    expectValid('RealtimeToken', await connection.json());
    expectValid('RealtimeToken', await (await token('agentbob', 'chat:inbox')).json());
    expect((await token('userone', `chat:conversation:${id}`)).status()).toBe(200);
    await expectProblem(await token('userone', 'chat:inbox'), 403);
    await expectProblem(await token('userone', 'chat:conversation:not-a-uuid'), 400);
    await expectProblem(await request.get(`${urls.api}/api/v1/demo/realtime/connection-token?actorId=admin`), 400);
  });
});

test.describe('realtime event contract (live Centrifugo)', () => {
  const centrifugoWs = process.env.CENTRIFUGO_WS_URL ?? 'ws://localhost:8000/connection/websocket';

  const tokenFor = (request: APIRequestContext, path: string) => async () =>
    (await (await request.get(`${urls.api}/api/v1/demo/realtime/${path}`)).json()).token as string;

  test('message.created is delivered after commit and matches the JSON Schema', async ({ request }) => {
    const { id } = await (await request.post(`${urls.api}/api/v1/demo/conversations`)).json();
    const channel = `chat:conversation:${id}`;
    const client = new Centrifuge(centrifugoWs, { getToken: tokenFor(request, 'connection-token?actorId=agentbob') });
    const received: unknown[] = [];
    const sub = client.newSubscription(channel, {
      getToken: tokenFor(request, `subscription-token?actorId=agentbob&channel=${encodeURIComponent(channel)}`),
    });
    sub.on('publication', (ctx: PublicationContext) => received.push(ctx.data));
    const subscribed = new Promise((resolve) => sub.once('subscribed', resolve));
    sub.subscribe();
    client.connect();
    try {
      await subscribed;
      const sent = await request.post(`${urls.api}/api/v1/demo/conversations/${id}/messages`, {
        data: { actorId: 'userone', text: `event-contract-${Date.now()}` },
      });
      const { message, realtimeDelivery } = await sent.json();
      expect(realtimeDelivery).toBe('PUBLISHED');
      await expect.poll(() => received.length, { timeout: 5000 }).toBeGreaterThan(0);
      const event = received.find((e) => (e as { message?: { id: string } }).message?.id === message.id);
      expectValid(`${SCHEMA_BASE}chat/demo-message-created.v1.schema.json`, event);

      // Client-side publication is disabled for the chat namespace.
      await expect(sub.publish({ type: 'message.created' })).rejects.toMatchObject({ code: 103 });
    } finally {
      client.disconnect();
    }
  });

  test('Centrifugo rejects invalid connection tokens and token-less private subscriptions', async ({ request }) => {
    const bad = new Centrifuge(centrifugoWs, { token: 'not-a-jwt' });
    const disconnected = new Promise<{ code: number }>((resolve) => bad.once('disconnected', resolve));
    bad.connect();
    expect((await disconnected).code).toBe(3500);

    const client = new Centrifuge(centrifugoWs, { getToken: tokenFor(request, 'connection-token?actorId=userone') });
    const sub = client.newSubscription('chat:inbox');
    // The SDK reports a server-side permission denial as a terminal 'unsubscribed' with the server error code.
    const rejected = new Promise<{ code: number }>((resolve) => sub.once('unsubscribed', resolve));
    sub.subscribe();
    client.connect();
    try {
      expect((await rejected).code).toBe(103);
    } finally {
      client.disconnect();
    }
  });
});
