import { expect, test } from '@playwright/test';
import { urls } from '../playwright.config';
import { adminHeaders, agentSession, freeAgentCapacity, purge, sendMessage, widgetSession } from '../support/api';
import { queryDb } from '../support/db';
import { agentSend, inboxItem, openDashboard, openWidget, watchErrors, widgetSend } from '../support/ui';

/**
 * Master spec 12.3 — visitor (Lit widget) and agent (Next.js dashboard) in two browser contexts, with persisted state
 * asserted through the API / PostgreSQL rather than rendered text only.
 */
test('visitorAndAgentFullLifecycle', async ({ browser, request }) => {
  test.setTimeout(300_000);
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const errors: string[] = [];
  const agentApi = await agentSession(request);
  await freeAgentCapacity(request, agentApi);
  const visitorSession = await widgetSession(request, `e2e-${runId}`);

  const visitorContext = await browser.newContext();
  const agentContext = await browser.newContext();
  const visitor = await visitorContext.newPage();
  const agent = await agentContext.newPage();
  watchErrors(visitor, 'visitor', errors);
  watchErrors(agent, 'agent', errors);

  // (3) dashboard sets ONLINE → the API agrees.
  await openDashboard(agent);
  await agent.getByTestId('availability-select').selectOption('ONLINE');
  await expect
    .poll(async () => (await (await request.get(`${urls.api}/api/v1/agent/me`, { headers: agentApi.headers })).json()).availability)
    .toBe('ONLINE');

  // (4) widget starts a conversation.
  const { widget, log, conversationId } = await openWidget(visitor, visitorSession);
  await expect(widget.getByTestId('conversation-status')).toHaveText('OPEN');
  const id = await conversationId();

  // (5) the item shows up in the queue (or already in "mine" if assignment was instant) within 10 s.
  const item = inboxItem(agent, id);
  await expect(item).toBeVisible({ timeout: 10_000 });
  await item.click();
  const agentLog = agent.getByTestId('agent-messages');
  await expect(agentLog).toHaveAttribute('data-conversation-id', id);
  await expect(agentLog).toHaveAttribute('data-subscribed', 'true');

  // (6) assignment resolves to ASSIGNED agentbob (agent is ONLINE).
  await expect(agent.getByTestId('assignment-state')).toHaveText('ASSIGNED', { timeout: 15_000 });
  await expect(agent.getByTestId('conversation-meta')).toContainText('agentbob');

  // (7) visitor sends → DB row, outbox PUBLISHED, agent sees it live.
  const visitorText = `hello-${runId}`;
  await widgetSend(widget, visitorText);
  await expect(log.getByText(visitorText)).toHaveCount(1);
  await expect(agentLog.getByText(visitorText)).toBeVisible({ timeout: 10_000 });
  const rows = await queryDb<{ sequence: string; sender_type: string }>(
    'SELECT sequence::text, sender_type FROM messages WHERE conversation_id = $1::uuid AND body = $2',
    [id, visitorText],
  );
  expect(rows).toEqual([{ sequence: '1', sender_type: 'CONTACT' }]);
  await expect
    .poll(
      async () =>
        (
          await queryDb<{ status: string }>(
            "SELECT status FROM outbox_events WHERE aggregate_id = $1 AND event_type = 'message.created'",
            [id],
          )
        ).map((r) => r.status),
      { timeout: 10_000 },
    )
    .toEqual(['PUBLISHED']);

  // (8) agent replies → visitor sees it live, nothing is duplicated.
  const agentText = `reply-${runId}`;
  await agentSend(agent, agentText);
  await expect(agentLog.getByText(agentText)).toHaveCount(1);
  await expect(log.getByText(agentText)).toBeVisible({ timeout: 10_000 });
  await expect(log.getByText(visitorText)).toHaveCount(1);

  // (9) typing shows and clears on the agent side without persisting anything; presence follows availability.
  const countMessages = async () =>
    Number((await queryDb<{ n: string }>('SELECT count(*)::text AS n FROM messages WHERE conversation_id = $1::uuid', [id]))[0].n);
  const before = await countMessages();
  await widget.getByLabel('Message', { exact: true }).pressSequentially('typing…', { delay: 50 });
  await expect(agent.getByTestId('typing-indicator')).toBeVisible({ timeout: 10_000 });
  await widget.getByLabel('Message', { exact: true }).fill('');
  await expect(agent.getByTestId('typing-indicator')).toBeHidden({ timeout: 15_000 });
  expect(await countMessages()).toBe(before);

  await expect(widget.getByTestId('agent-presence')).toContainText('Online', { timeout: 40_000 });
  await agent.getByTestId('availability-select').selectOption('AWAY');
  await expect(widget.getByTestId('agent-presence')).not.toContainText('Online', { timeout: 40_000 });
  await agent.getByTestId('availability-select').selectOption('ONLINE');

  // (10) reload the widget mid-conversation: history complete and subscribed again.
  await visitor.reload();
  await expect(widget.getByTestId('connection-state')).toHaveText('Live');
  await expect(widget.getByTestId('widget-messages')).toHaveAttribute('data-subscribed', 'true');
  await expect(widget.getByTestId('widget-messages').getByText(visitorText)).toHaveCount(1);
  await expect(widget.getByTestId('widget-messages').getByText(agentText)).toHaveCount(1);

  // (11) lifecycle: resolve → auto-reopen → resolve → archive/unarchive → reopen → archive → new conversation.
  await agent.getByTestId('action-resolve').click();
  await expect(widget.getByTestId('conversation-status')).toHaveText('RESOLVED');
  const afterResolve = `again-${runId}`;
  await widgetSend(widget, afterResolve);
  await expect(widget.getByTestId('conversation-status')).toHaveText('OPEN', { timeout: 15_000 });
  await expect
    .poll(
      async () =>
        (
          await queryDb<{ n: string }>(
            "SELECT count(*)::text AS n FROM conversation_state_events WHERE conversation_id = $1::uuid AND event_type = 'REOPENED_BY_CONTACT'",
            [id],
          )
        )[0].n,
    )
    .toBe('1');
  await expect(agent.getByTestId('assignment-state')).toHaveText('ASSIGNED', { timeout: 20_000 });

  // Resolve again; once the worker's release shows NONE, archive uses the fresh version and raises no STALE_VERSION.
  await agent.getByTestId('action-resolve').click();
  await expect(agent.getByTestId('assignment-state')).toHaveText('NONE', { timeout: 20_000 });
  await agent.getByTestId('action-archive').click();
  await expect(agent.getByTestId('action-unarchive')).toBeVisible();
  await expect(agent.getByTestId('agent-notice')).toHaveCount(0);
  await agent.getByTestId('all-tab').click();
  await expect(inboxItem(agent, id)).toHaveCount(0);
  await agent.getByTestId('archived-toggle').check();
  await expect(inboxItem(agent, id)).toBeVisible();
  await agent.getByTestId('action-unarchive').click();
  await agent.getByTestId('archived-toggle').uncheck();
  await expect(inboxItem(agent, id)).toBeVisible();

  await agent.getByTestId('action-reopen').click();
  await expect(widget.getByTestId('conversation-status')).toHaveText('OPEN', { timeout: 15_000 });
  await expect(widget.getByTestId('widget-messages').getByText(visitorText)).toHaveCount(1);
  await expect(agent.getByTestId('assignment-state')).toHaveText('ASSIGNED', { timeout: 20_000 });
  await agent.getByTestId('action-resolve').click();
  await expect(agent.getByTestId('assignment-state')).toHaveText('NONE', { timeout: 20_000 });
  await agent.getByTestId('action-archive').click();
  await expect(agent.getByTestId('action-unarchive')).toBeVisible();

  // The archive reaches the widget live: it shows Closed and offers a new conversation. A send into the archived
  // conversation is refused with 409 CONVERSATION_ARCHIVED.
  await expect(widget.getByTestId('conversation-status')).toHaveText('Closed', { timeout: 10_000 });
  const refused = await sendMessage(request, 'widget', visitorSession, id, `after-archive-${runId}`);
  expect(refused.status()).toBe(409);
  expect((await refused.json()).code).toBe('CONVERSATION_ARCHIVED');
  await expect(widget.getByTestId('new-conversation')).toBeVisible();
  await widget.getByTestId('new-conversation').click();
  await expect(widget.getByTestId('new-conversation')).toHaveCount(0);
  await expect(widget.getByTestId('widget-messages')).not.toHaveAttribute('data-conversation-id', id);
  const newId = await widget.getByTestId('widget-messages').getAttribute('data-conversation-id');
  expect(newId).toMatch(/^[0-9a-f-]{36}$/);

  // (12) admin retention: dry run touches nothing, the real purge removes the old resolved conversation only.
  expect((await request.put(`${urls.api}/api/v1/admin/retention/policies/${visitorSession.tenantId}`, {
    headers: adminHeaders(),
    data: { resolvedRetentionDays: 30 },
  })).status()).toBe(200);
  const cutoff = new Date(Date.now() + 60_000).toISOString();
  const dry = await (await purge(request, visitorSession.tenantId, { dryRun: true, cutoff })).json();
  expect(dry.messagesDeleted).toBeGreaterThan(0);
  expect(await countMessages()).toBeGreaterThan(0);

  const real = await (await purge(request, visitorSession.tenantId, { dryRun: false, cutoff })).json();
  expect(real.status).toBe('COMPLETED');
  expect(await countMessages()).toBe(0);
  const purged = await queryDb<{ id: string; purged: boolean }>(
    'SELECT id::text, (purged_at IS NOT NULL) AS purged FROM conversations WHERE id = ANY($1::uuid[])',
    [[id, newId]],
  );
  expect(Object.fromEntries(purged.map((r) => [r.id, r.purged]))).toEqual({ [id]: true, [newId as string]: false });

  expect(errors).toEqual([]);
  await visitorContext.close();
  await agentContext.close();
});
