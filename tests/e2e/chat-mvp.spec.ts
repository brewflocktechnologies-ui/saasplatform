import { expect, test, type Page } from '@playwright/test';
import { urls } from '../playwright.config';
import { queryDb } from '../support/db';

/**
 * specs/mvpsetup.spec.md §8.3 — two independent browser contexts (visitor userone in the Lit widget, agent agentbob
 * in the Next.js dashboard). Live delivery is asserted WITHOUT reloading; reload is only used afterwards to prove
 * history comes back from the API, and rows are checked directly in PostgreSQL.
 */
test('userone and agentbob exchange messages live; history persists in PostgreSQL', async ({ browser }) => {
  test.setTimeout(90_000);
  const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const visitorText = `hello-from-userone-${runId}`;
  const agentText = `reply-from-agentbob-${runId}`;

  const errors: string[] = [];
  const watch = (page: Page, who: string) => {
    page.on('console', (m) => m.type() === 'error' && errors.push(`${who}: ${m.text()}`));
    page.on('pageerror', (e) => errors.push(`${who}: ${e.message}`));
  };

  const visitorContext = await browser.newContext();
  const agentContext = await browser.newContext();
  const visitor = await visitorContext.newPage();
  const agent = await agentContext.newPage();
  watch(visitor, 'visitor');
  watch(agent, 'agent');

  // 1–2. Both pages ready, connected to Centrifugo and subscribed to the same conversation channel.
  await visitor.goto(`${urls.widget}/demo`);
  const widget = visitor.locator('chat-platform-widget');
  await expect(visitor.getByTestId('demo-visitor')).toHaveText('Demo visitor: userone');
  await expect(widget.getByTestId('connection-state')).toHaveText('Live');
  const widgetLog = widget.getByTestId('widget-messages');
  await expect(widgetLog).toHaveAttribute('data-subscribed', 'true');
  const conversationId = await widgetLog.getAttribute('data-conversation-id');
  expect(conversationId).toMatch(/^[0-9a-f-]{36}$/);

  await agent.goto(`${urls.dashboard}/agent`);
  await expect(agent.getByTestId('agent-id')).toHaveText('Demo agent: agentbob');
  await expect(agent.getByTestId('connection-state')).toHaveText('Live');
  await expect(agent.locator(`[data-testid="inbox-item"][data-conversation-id="${conversationId}"]`)).toBeVisible();
  const agentLog = agent.getByTestId('agent-messages');
  await expect(agentLog).toHaveAttribute('data-conversation-id', conversationId ?? '');
  await expect(agentLog).toHaveAttribute('data-subscribed', 'true');

  // 3–4. Visitor -> API -> PostgreSQL -> Centrifugo -> agent, no reload of the agent page.
  await widget.getByLabel('Message', { exact: true }).fill(visitorText);
  await widget.getByRole('button', { name: 'Send' }).click();
  await expect(widgetLog.getByText(visitorText)).toHaveCount(1);
  await expect(agentLog.getByText(visitorText)).toBeVisible({ timeout: 10_000 });

  // 5–6. Agent -> ... -> visitor, no reload of the widget page.
  await agent.getByLabel('Reply').fill(agentText);
  await agent.getByRole('button', { name: 'Send' }).click();
  await expect(agentLog.getByText(agentText)).toHaveCount(1);
  await expect(widgetLog.getByText(agentText)).toBeVisible({ timeout: 10_000 });
  // Sender's own HTTP response + its own WebSocket event must not render twice.
  await expect(widgetLog.getByText(visitorText)).toHaveCount(1);

  // 7. Persistence: reload both pages, history comes back from the API.
  await visitor.reload();
  await agent.reload();
  for (const log of [widget.getByTestId('widget-messages'), agent.getByTestId('agent-messages')]) {
    await expect(log.getByText(visitorText)).toHaveCount(1);
    await expect(log.getByText(agentText)).toHaveCount(1);
  }

  // 8. Database assertion (not inferred from UI text).
  const rows = await queryDb<{ conversation_id: string; sender_id: string; sender_role: string; body: string }>(
    'SELECT conversation_id::text, sender_id, sender_role, body FROM chat_messages WHERE body = ANY($1) ORDER BY created_at',
    [[visitorText, agentText]],
  );
  expect(rows).toEqual([
    { conversation_id: conversationId, sender_id: 'userone', sender_role: 'VISITOR', body: visitorText },
    { conversation_id: conversationId, sender_id: 'agentbob', sender_role: 'AGENT', body: agentText },
  ]);

  // 9. No unexpected browser errors.
  expect(errors).toEqual([]);

  await visitorContext.close();
  await agentContext.close();
});
