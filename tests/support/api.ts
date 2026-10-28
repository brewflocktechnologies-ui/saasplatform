import { expect, type APIRequestContext } from '@playwright/test';
import { urls } from '../playwright.config';
import { localEnv } from './db';

/** API helpers shared by the contract and E2E suites. All identities are the LOCAL POC seed (not authentication). */
export const WIDGET_KEY = process.env.WIDGET_KEY ?? 'wk_local_demo';

export interface Session {
  token: string;
  expiresAt: string;
  tenantId: string;
  contactId?: string;
  displayName?: string;
  headers: { Authorization: string };
}

const bearer = (token: string) => ({ Authorization: `Bearer ${token}` });

export async function widgetSession(request: APIRequestContext, externalId: string): Promise<Session> {
  const res = await request.post(`${urls.api}/api/v1/widget/sessions`, {
    data: { widgetKey: WIDGET_KEY, externalId, displayName: externalId },
  });
  expect([200, 201]).toContain(res.status());
  const body = await res.json();
  return {
    token: body.sessionToken,
    expiresAt: body.expiresAt,
    tenantId: body.tenantId,
    contactId: body.contact.id,
    displayName: body.contact.displayName ?? undefined,
    headers: bearer(body.sessionToken),
  };
}

export async function agentSession(request: APIRequestContext, agentId = 'agentbob'): Promise<Session> {
  const res = await request.post(`${urls.api}/api/v1/agent/local-login`, { data: { agentId } });
  expect(res.status()).toBe(200);
  const body = await res.json();
  return { token: body.sessionToken, expiresAt: body.expiresAt, tenantId: body.tenantId, headers: bearer(body.sessionToken) };
}

export function adminHeaders(): Record<string, string> {
  const token = process.env.APP_LOCAL_ADMIN_TOKEN ?? localEnv()['APP_LOCAL_ADMIN_TOKEN'] ?? '';
  return { 'X-Admin-Token': token };
}

export async function startConversation(request: APIRequestContext, session: Session) {
  const res = await request.post(`${urls.api}/api/v1/widget/conversations`, { headers: session.headers });
  expect([200, 201]).toContain(res.status());
  return res.json();
}

export async function sendMessage(
  request: APIRequestContext,
  area: 'widget' | 'agent',
  session: Session,
  conversationId: string,
  body: string,
  clientMessageId: string = crypto.randomUUID(),
) {
  return request.post(`${urls.api}/api/v1/${area}/conversations/${conversationId}/messages`, {
    headers: session.headers,
    data: { clientMessageId, body },
  });
}

export async function resolve(request: APIRequestContext, agent: Session, conversationId: string, expectedVersion?: number) {
  return request.post(`${urls.api}/api/v1/agent/conversations/${conversationId}/resolve`, {
    headers: agent.headers,
    data: expectedVersion === undefined ? {} : { expectedVersion },
  });
}

export async function purge(
  request: APIRequestContext,
  tenantId: string,
  options: { dryRun: boolean; cutoff?: string },
) {
  return request.post(`${urls.api}/api/v1/admin/retention/purge`, {
    headers: adminHeaders(),
    data: { tenantId, ...options },
  });
}

export async function getAgentConversation(request: APIRequestContext, agent: Session, conversationId: string) {
  const res = await request.get(`${urls.api}/api/v1/agent/conversations/${conversationId}`, { headers: agent.headers });
  expect(res.status()).toBe(200);
  return res.json();
}

export async function setPresence(request: APIRequestContext, agent: Session, availability: 'ONLINE' | 'AWAY' | 'OFFLINE') {
  const res = await request.put(`${urls.api}/api/v1/agent/presence`, { headers: agent.headers, data: { availability } });
  expect(res.status()).toBe(200);
}

/**
 * Archives once the release worker has moved the assignment to NONE: the release bumps `version` together with an event
 * (R-P23), so the archive must use the version read after it.
 */
export async function archiveWhenReleased(request: APIRequestContext, agent: Session, conversationId: string) {
  await expect
    .poll(async () => (await getAgentConversation(request, agent, conversationId)).assignment.state, { timeout: 20_000 })
    .toBe('NONE');
  const current = await getAgentConversation(request, agent, conversationId);
  const res = await request.post(`${urls.api}/api/v1/agent/conversations/${conversationId}/archive`, {
    headers: agent.headers,
    data: { expectedVersion: current.version },
  });
  expect(res.status()).toBe(200);
}

/**
 * Resolves every conversation currently assigned to or queued for the agent and waits until the release worker has freed the capacity:
 * contract and E2E runs leave assigned conversations behind, which would otherwise answer QUEUED_CAPACITY_EXHAUSTED.
 */
export async function freeAgentCapacity(request: APIRequestContext, agent: Session) {
  const inboxes = await (await request.get(`${urls.api}/api/v1/agent/inboxes`, { headers: agent.headers })).json();
  const open = async () => {
    const found: Array<{ id: string }> = [];
    for (const inbox of inboxes) {
      for (const view of ['mine', 'queue']) {
        const res = await request.get(
          `${urls.api}/api/v1/agent/inboxes/${inbox.id}/conversations?view=${view}&limit=100`,
          { headers: agent.headers },
        );
        found.push(...(await res.json()).items);
      }
    }
    return found;
  };
  // The queue is drained too: a freed slot is otherwise taken by an older queued conversation within one sweep.
  await expect
    .poll(
      async () => {
        const left = await open();
        for (const conversation of left) await resolve(request, agent, conversation.id);
        return left.length;
      },
      { timeout: 90_000, intervals: [1000, 2000, 3000] },
    )
    .toBe(0);
}
