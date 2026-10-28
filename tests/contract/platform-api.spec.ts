import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type APIRequestContext } from '@playwright/test';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { Centrifuge, type PublicationContext } from 'centrifuge';
import { parse } from 'yaml';
import { urls } from '../playwright.config';
import { agentSession, resolve, sendMessage, startConversation, widgetSession, type Session } from '../support/api';

/**
 * Validates live backend responses against libs/contracts/openapi/platform-api.yaml (OpenAPI 3.1 = JSON Schema
 * 2020-12) and live Centrifugo events against libs/contracts/json-schema.
 */
const contracts = join(__dirname, '..', '..', 'libs', 'contracts');
const SCHEMA_BASE = 'https://schemas.chat-platform.local/chat/';

// OpenAPI references the event/DTO schemas by relative file path; point those refs at the schemas' $id instead.
const rebase = (node: unknown): unknown =>
  Array.isArray(node)
    ? node.map(rebase)
    : node && typeof node === 'object'
      ? Object.fromEntries(
          Object.entries(node).map(([k, v]) =>
            k === '$ref' && typeof v === 'string' && v.startsWith('../json-schema/')
              ? [k, 'https://schemas.chat-platform.local/' + v.slice('../json-schema/'.length)]
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

async function expectProblem(res: Awaited<ReturnType<APIRequestContext['get']>>, status: number, code?: string) {
  expect(res.status()).toBe(status);
  expect(res.headers()['content-type']).toContain('application/problem+json');
  const body = (await res.json()) as { code?: string };
  expectValid('Problem', body);
  if (code) expect(body.code).toBe(code);
  expect(JSON.stringify(body)).not.toMatch(/stack ?trace|exception|sql|secret|password/i);
}

const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

test.describe('platform-api contract (live backend)', () => {
  test('statusConformsToStatusResponse', async ({ request }) => {
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

  test('actuator health is UP', async ({ request }) => {
    const res = await request.get(`${urls.api}/actuator/health`);
    expect(res.status()).toBe(200);
    expect((await res.json()).status).toBe('UP');
  });

  test('widgetSessionAndConversationConformToSchemas', async ({ request }) => {
    const res = await request.post(`${urls.api}/api/v1/widget/sessions`, {
      data: { widgetKey: 'wk_local_demo', externalId: unique('contract'), displayName: 'Contract' },
    });
    expect([200, 201]).toContain(res.status());
    expectValid('WidgetSessionResponse', await res.json());

    const session = await widgetSession(request, unique('contract'));
    const conversation = await startConversation(request, session);
    expectValid('ConversationView', conversation);
    // The contact view never carries routing details (R-P3).
    expect(Object.keys(conversation.assignment).sort()).toEqual(['agentId', 'state']);

    const again = await request.post(`${urls.api}/api/v1/widget/conversations`, { headers: session.headers });
    expect(again.status()).toBe(200);
    expect((await again.json()).id).toBe(conversation.id);
  });

  test('messagePageConforms', async ({ request }) => {
    const session = await widgetSession(request, unique('contract'));
    const { id } = await startConversation(request, session);
    for (const n of [1, 2, 3]) {
      const sent = await sendMessage(request, 'widget', session, id, `contract ${n}`);
      expect(sent.status()).toBe(201);
      expectValid('MessageView', await sent.json());
    }
    const page = await request.get(`${urls.api}/api/v1/widget/conversations/${id}/messages?limit=2`, {
      headers: session.headers,
    });
    expect(page.status()).toBe(200);
    const body = await page.json();
    expectValid('MessagePage', body);
    expect(body.items).toHaveLength(2);
    expect(body.hasMore).toBe(true);
    const sequences = body.items.map((m: { sequence: number }) => m.sequence);
    expect(sequences).toEqual([...sequences].sort((a: number, b: number) => a - b));
  });

  test('agentInboxConforms', async ({ request }) => {
    const agent = await agentSession(request);
    const me = await request.get(`${urls.api}/api/v1/agent/me`, { headers: agent.headers });
    expect(me.status()).toBe(200);
    expectValid('AgentMeView', await me.json());

    const inboxes = await request.get(`${urls.api}/api/v1/agent/inboxes`, { headers: agent.headers });
    expect(inboxes.status()).toBe(200);
    const list = await inboxes.json();
    expect(list.length).toBeGreaterThan(0);
    list.forEach((i: unknown) => expectValid('InboxView', i));

    const page = await request.get(`${urls.api}/api/v1/agent/inboxes/${list[0].id}/conversations?view=all`, {
      headers: agent.headers,
    });
    expect(page.status()).toBe(200);
    const body = await page.json();
    expectValid('InboxPage', body);
  });

  test('problemsCarryCodeAndNoSecrets', async ({ request }) => {
    const session = await widgetSession(request, unique('contract'));
    const other = await widgetSession(request, unique('contract'));
    const { id } = await startConversation(request, session);

    await expectProblem(await request.get(`${urls.api}/api/v1/widget/conversations/${id}`), 401, 'UNAUTHENTICATED');
    // A contact token is not an agent token.
    await expectProblem(
      await request.get(`${urls.api}/api/v1/agent/inboxes`, { headers: session.headers }),
      403,
      'FORBIDDEN',
    );
    // Another contact's conversation is indistinguishable from a missing one (404, R-P22) or forbidden.
    const foreign = await request.get(`${urls.api}/api/v1/widget/conversations/${id}`, { headers: other.headers });
    expect([403, 404]).toContain(foreign.status());
    await expectProblem(foreign, foreign.status());
    await expectProblem(await request.get(`${urls.api}/api/v1/does-not-exist`), 404, 'NOT_FOUND');

    // 409: a resolved conversation rejects a visitor reply path only after the agent resolved it.
    const agent = await agentSession(request);
    const resolved = await resolve(request, agent, id);
    expect(resolved.status()).toBe(200);
    const reply = await sendMessage(request, 'agent', agent, id, 'late reply');
    await expectProblem(reply, 409, 'CONVERSATION_RESOLVED');
  });

  test('rateLimitedRequestsAreProblems (429 sample, when the typing limiter trips)', async ({ request }) => {
    const session = await widgetSession(request, unique('contract'));
    const { id } = await startConversation(request, session);
    const url = `${urls.api}/api/v1/widget/conversations/${id}/typing`;
    const statuses: number[] = [];
    for (let i = 0; i < 3; i++) {
      const res = await request.post(url, { headers: session.headers, data: { typing: true } });
      statuses.push(res.status());
      if (res.status() === 429) {
        await expectProblem(res, 429, 'RATE_LIMITED');
        return;
      }
    }
    test.skip(true, `typing limiter did not answer 429 (observed ${statuses.join(', ')}); no 429 sample available`);
  });
});

test.describe('realtime event contract (live Centrifugo)', () => {
  const centrifugoWs = process.env.CENTRIFUGO_WS_URL ?? 'ws://localhost:8000/connection/websocket';

  const connectionToken = (request: APIRequestContext, area: 'widget' | 'agent', session: Session) => async () =>
    (await (await request.get(`${urls.api}/api/v1/${area}/realtime/connection-token`, { headers: session.headers })).json())
      .token as string;
  const subscriptionToken =
    (request: APIRequestContext, area: 'widget' | 'agent', session: Session, channel: string) => async () =>
      (
        await (
          await request.get(`${urls.api}/api/v1/${area}/realtime/subscription-token`, {
            headers: session.headers,
            params: { channel },
          })
        ).json()
      ).token as string;

  /** Subscribes as the contact of `conversationId`; resolves once subscribed. */
  async function contactSubscription(request: APIRequestContext, session: Session, conversationId: string) {
    const channel = `chat:conversation:${conversationId}`;
    const client = new Centrifuge(centrifugoWs, { getToken: connectionToken(request, 'widget', session) });
    const received: Array<{ eventType: string; data: unknown } & Record<string, unknown>> = [];
    const sub = client.newSubscription(channel, { getToken: subscriptionToken(request, 'widget', session, channel) });
    sub.on('publication', (ctx: PublicationContext) => received.push(ctx.data));
    const subscribed = new Promise((resolve) => sub.once('subscribed', resolve));
    sub.subscribe();
    client.connect();
    await subscribed;
    return { client, sub, received };
  }

  test('liveMessageCreatedEventConformsToSchema', async ({ request }) => {
    const session = await widgetSession(request, unique('contract'));
    const { id } = await startConversation(request, session);
    const { client, sub, received } = await contactSubscription(request, session, id);
    try {
      const sent = await sendMessage(request, 'widget', session, id, unique('event-contract'));
      const message = await sent.json();
      await expect
        .poll(() => received.find((e) => e.eventType === 'message.created'), { timeout: 10_000 })
        .toBeTruthy();
      const event = received.find(
        (e) => e.eventType === 'message.created' && (e.data as { message: { id: string } }).message.id === message.id,
      );
      expectValid(`${SCHEMA_BASE}message-created.v1.schema.json`, event);

      // Client-side publication is disabled for the chat namespace.
      await expect(sub.publish({ eventType: 'message.created' })).rejects.toMatchObject({ code: 103 });
    } finally {
      client.disconnect();
    }
  });

  test('liveConversationUpdatedEventConforms', async ({ request }) => {
    const session = await widgetSession(request, unique('contract'));
    const { id } = await startConversation(request, session);
    const { client, received } = await contactSubscription(request, session, id);
    try {
      const agent = await agentSession(request);
      expect((await resolve(request, agent, id)).status()).toBe(200);
      await expect
        .poll(() => received.find((e) => e.eventType === 'conversation.updated'), { timeout: 10_000 })
        .toBeTruthy();
      const event = received.find((e) => e.eventType === 'conversation.updated');
      expectValid(`${SCHEMA_BASE}conversation-updated.v1.schema.json`, event);
    } finally {
      client.disconnect();
    }
  });

  test('liveTypingEventConforms', async ({ request }) => {
    const session = await widgetSession(request, unique('contract'));
    const { id } = await startConversation(request, session);
    const { client, received } = await contactSubscription(request, session, id);
    try {
      const agent = await agentSession(request);
      const res = await request.post(`${urls.api}/api/v1/agent/conversations/${id}/typing`, {
        headers: agent.headers,
        data: { typing: true },
      });
      expect(res.status()).toBeLessThan(300);
      await expect.poll(() => received.find((e) => e.eventType === 'typing'), { timeout: 10_000 }).toBeTruthy();
      expectValid(`${SCHEMA_BASE}typing.v1.schema.json`, received.find((e) => e.eventType === 'typing'));
    } finally {
      client.disconnect();
    }
  });

  test('invalidConnectionTokenIsRejected', async () => {
    const bad = new Centrifuge(centrifugoWs, { getToken: async () => 'not-a-jwt' });
    const disconnected = new Promise<{ code: number }>((resolve) => bad.once('disconnected', resolve));
    bad.connect();
    try {
      // Centrifugo answers an unparsable token with its invalid-token disconnect code (observed: 3500).
      expect((await disconnected).code).toBe(3500);
    } finally {
      bad.disconnect();
    }
  });

  test('subscriptionTokenForAnotherChannelIsRejected', async ({ request }) => {
    const session = await widgetSession(request, unique('contract'));
    const other = await widgetSession(request, unique('contract'));
    const { id } = await startConversation(request, session);
    const foreignChannel = `chat:conversation:${id}`;

    // The backend refuses to mint a token for a channel the caller does not participate in...
    const denied = await request.get(`${urls.api}/api/v1/widget/realtime/subscription-token`, {
      headers: other.headers,
      params: { channel: foreignChannel },
    });
    expect([403, 404]).toContain(denied.status());

    // ...and Centrifugo rejects a token-less subscription as a terminal 'unsubscribed' with code 103.
    const client = new Centrifuge(centrifugoWs, { getToken: connectionToken(request, 'widget', other) });
    const sub = client.newSubscription(foreignChannel);
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
