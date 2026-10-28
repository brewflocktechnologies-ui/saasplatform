import { Centrifuge } from 'centrifuge';
import { expect, test, type Browser, type BrowserContext } from '@playwright/test';
import { urls } from '../playwright.config';
import {
  agentSession,
  archiveWhenReleased,
  getAgentConversation,
  resolve,
  sendMessage,
  setPresence,
  startConversation,
  widgetSession,
} from '../support/api';
import { queryDb } from '../support/db';
import { dockerAvailable, startService, stopService } from '../support/stack';
import { inboxItem, openConversation, openDashboard, openWidget, widgetSend } from '../support/ui';

const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

const messageRows = (conversationId: string, body?: string) =>
  queryDb<{ body: string }>(
    `SELECT body FROM messages WHERE conversation_id = $1::uuid ${body ? 'AND body = $2' : ''} ORDER BY sequence`,
    body ? [conversationId, body] : [conversationId],
  );

test.describe('resilience (serial, runs after the parallel suites)', () => {
  test.setTimeout(180_000);

  // Manually created contexts are not closed by Playwright; a leaked dashboard would keep heartbeating as agentbob.
  const contexts: BrowserContext[] = [];
  const newPage = async (browser: Browser) => {
    const context = await browser.newContext();
    contexts.push(context);
    return context.newPage();
  };
  test.afterEach(async () => {
    await Promise.all(contexts.splice(0).map((context) => context.close().catch(() => undefined)));
  });

  test('refreshDuringActiveConversation', async ({ page, request }) => {
    const session = await widgetSession(request, unique('refresh'));
    const { widget, log, conversationId } = await openWidget(page, session);
    const id = await conversationId();
    const first = unique('first');
    await widgetSend(widget, first);
    await expect(log.getByText(first)).toHaveCount(1);

    await page.reload();
    await expect(widget.getByTestId('widget-messages')).toHaveAttribute('data-subscribed', 'true');
    await expect(widget.getByTestId('widget-messages').getByText(first)).toHaveCount(1);
    const second = unique('second');
    await widgetSend(widget, second);
    await expect(widget.getByTestId('widget-messages').getByText(second)).toHaveCount(1);
    await expect.poll(async () => (await messageRows(id)).map((r) => r.body)).toEqual([first, second]);
  });

  test('messageDuringBootstrapIsNotLost', async ({ page, request }) => {
    const session = await widgetSession(request, unique('bootstrap'));
    const conversation = await startConversation(request, session);
    const agent = await agentSession(request);
    const text = unique('during-bootstrap');

    // Hold the history response while the agent replies: the live event arrives before the history does.
    await page.route('**/api/v1/widget/conversations/*/messages?*', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 3000));
      await route.continue();
    });
    const opening = openWidget(page, session);
    await sendMessage(request, 'agent', agent, conversation.id, text);
    const { widget } = await opening;
    await expect(widget.getByTestId('widget-messages').getByText(text)).toHaveCount(1);
    await page.waitForTimeout(1500);
    await expect(widget.getByTestId('widget-messages').getByText(text)).toHaveCount(1);
  });

  test('centrifugoRestartConverges', async ({ page, request }) => {
    test.skip(!dockerAvailable(), 'docker is unavailable: cannot stop and start Centrifugo');
    const session = await widgetSession(request, unique('restart'));
    const conversation = await startConversation(request, session);
    await openDashboard(page);
    await openConversation(page, conversation.id);
    const bodies = [unique('during-outage-1'), unique('during-outage-2')];

    stopService('centrifugo');
    try {
      for (const body of bodies) {
        expect((await sendMessage(request, 'widget', session, conversation.id, body)).status()).toBe(201);
      }
      expect((await messageRows(conversation.id)).map((r) => r.body)).toEqual(bodies);
    } finally {
      startService('centrifugo');
    }

    const log = page.getByTestId('agent-messages');
    for (const body of bodies) await expect(log.getByText(body)).toHaveCount(1, { timeout: 30_000 });
    await expect
      .poll(
        async () =>
          (
            await queryDb<{ status: string }>(
              "SELECT DISTINCT status FROM outbox_events WHERE aggregate_id = $1 AND event_type = 'message.created'",
              [conversation.id],
            )
          ).map((r) => r.status),
        { timeout: 60_000 },
      )
      .toEqual(['PUBLISHED']);
  });

  test('duplicateSubmissionAfterTimeoutCreatesOneRow', async ({ page, request }) => {
    const session = await widgetSession(request, unique('timeout'));
    // Drop live publications so the client cannot learn of the commit from its own message.created event.
    await page.routeWebSocket('**/connection/websocket', (ws) => {
      const server = ws.connectToServer();
      ws.onMessage((message) => server.send(message));
      server.onMessage((message) => {
        if (!String(message).includes('"pub"')) ws.send(message);
      });
    });
    const { widget, conversationId } = await openWidget(page, session);
    const id = await conversationId();
    const text = unique('once');

    let first = true;
    await page.route('**/api/v1/widget/conversations/*/messages', async (route) => {
      if (route.request().method() !== 'POST' || !first) return route.continue();
      first = false;
      await route.fetch(); // the server handles it and commits...
      await new Promise((resolve) => setTimeout(resolve, 9000)); // ...but the answer arrives after the client's 8 s timeout
      await route.abort('timedout');
    });
    await widgetSend(widget, text);
    await expect(widget.getByTestId('retry')).toBeVisible({ timeout: 20_000 });
    await widget.getByTestId('retry').click();
    await expect(widget.getByTestId('retry')).toHaveCount(0);
    await expect(widget.getByTestId('widget-messages').getByText(text)).toHaveCount(1);
    expect(await messageRows(id, text)).toHaveLength(1);
  });

  test('typingIndicatorTimesOutWhenStopIsLost', async ({ browser, request }) => {
    const session = await widgetSession(request, unique('typing'));
    const visitorPage = await newPage(browser);
    const agentPage = await newPage(browser);
    const { widget, conversationId } = await openWidget(visitorPage, session);
    const id = await conversationId();
    await visitorPage.route('**/api/v1/widget/conversations/*/typing', (route) =>
      route.request().postData()?.includes('"typing":false') ? route.abort() : route.continue(),
    );
    await openDashboard(agentPage);
    await openConversation(agentPage, id);

    await widget.getByLabel('Message', { exact: true }).pressSequentially('hmm', { delay: 50 });
    await expect(agentPage.getByTestId('typing-indicator')).toBeVisible({ timeout: 10_000 });
    // The stop signal never arrives; the receiver's own 5 s timeout clears the indicator.
    await expect(agentPage.getByTestId('typing-indicator')).toBeHidden({ timeout: 15_000 });
  });

  test('agentGoesOfflineAndStalePresenceExpires', async ({ browser, request }) => {
    test.setTimeout(240_000);
    const session = await widgetSession(request, unique('presence'));
    const agentApi = await agentSession(request);
    const visitorPage = await newPage(browser);
    const { widget } = await openWidget(visitorPage, session);
    const presence = widget.getByTestId('agent-presence');

    await setPresence(request, agentApi, 'OFFLINE');
    await expect(presence).toContainText('Offline', { timeout: 60_000 });

    // Online through a dashboard (30 s heartbeat), then the dashboard disappears without sending OFFLINE.
    const agentContext = await browser.newContext();
    contexts.push(agentContext);
    const agentPage = await agentContext.newPage();
    await openDashboard(agentPage);
    await agentPage.getByTestId('availability-select').selectOption('ONLINE');
    await expect(presence).toContainText('Online', { timeout: 60_000 });
    await agentContext.close();
    // last heartbeat ≤ 30 s old + 60 s stale-after + 5 s cache + 30 s widget poll
    await expect(presence).toContainText('Offline', { timeout: 100_000 });
  });

  test('archivedConversationIsAbsentFromActiveListButRetrievable', async ({ request }) => {
    const session = await widgetSession(request, unique('archived'));
    const { id } = await startConversation(request, session);
    const agent = await agentSession(request);
    expect((await resolve(request, agent, id)).status()).toBe(200);
    await archiveWhenReleased(request, agent, id);

    const inbox = (await (await request.get(`${urls.api}/api/v1/agent/inboxes`, { headers: agent.headers })).json())[0];
    const list = async (archived: boolean) =>
      (
        await (
          await request.get(`${urls.api}/api/v1/agent/inboxes/${inbox.id}/conversations?view=all&archived=${archived}&limit=100`, {
            headers: agent.headers,
          })
        ).json()
      ).items.map((c: { id: string }) => c.id);
    expect(await list(false)).not.toContain(id);
    expect(await list(true)).toContain(id);
    expect((await getAgentConversation(request, agent, id)).archivedAt).not.toBeNull();
  });

  test('reopenedConversationReturnsToInbox', async ({ page, request }) => {
    const session = await widgetSession(request, unique('reopen'));
    const { id } = await startConversation(request, session);
    const agent = await agentSession(request);
    expect((await resolve(request, agent, id)).status()).toBe(200);
    const current = await getAgentConversation(request, agent, id);
    const reopened = await request.post(`${urls.api}/api/v1/agent/conversations/${id}/reopen`, {
      headers: agent.headers,
      data: { expectedVersion: current.version },
    });
    expect(reopened.status()).toBe(200);
    expect((await reopened.json()).status).toBe('OPEN');

    await openDashboard(page);
    await page.getByTestId('all-tab').click();
    await expect(inboxItem(page, id)).toBeVisible();
    await expect(inboxItem(page, id).getByTestId('item-status')).toContainText('OPEN');
  });

  test('nonParticipantCannotLoadHistoryOrSubscribe', async ({ request }) => {
    const owner = await widgetSession(request, unique('owner'));
    const intruder = await widgetSession(request, unique('intruder'));
    const { id } = await startConversation(request, owner);
    const channel = `chat:conversation:${id}`;

    const history = await request.get(`${urls.api}/api/v1/widget/conversations/${id}/messages`, { headers: intruder.headers });
    expect([403, 404]).toContain(history.status());
    const token = await request.get(`${urls.api}/api/v1/widget/realtime/subscription-token`, {
      headers: intruder.headers,
      params: { channel },
    });
    expect([403, 404]).toContain(token.status());

    const ws = process.env.CENTRIFUGO_WS_URL ?? 'ws://localhost:8000/connection/websocket';
    const client = new Centrifuge(ws, {
      getToken: async () =>
        (await (await request.get(`${urls.api}/api/v1/widget/realtime/connection-token`, { headers: intruder.headers })).json()).token,
    });
    const sub = client.newSubscription(channel); // no subscription token
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
