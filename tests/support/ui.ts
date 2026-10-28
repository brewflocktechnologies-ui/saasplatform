import { expect, type Locator, type Page } from '@playwright/test';
import { urls } from '../playwright.config';
import type { Session } from './api';

export const WIDGET_KEY = process.env.WIDGET_KEY ?? 'wk_local_demo';

/** Collects console/page errors; expected 4xx resource errors (409 archived, 429) are ignored. */
export function watchErrors(page: Page, who: string, sink: string[]) {
  page.on('console', (m) => {
    if (m.type() === 'error' && !/status of (409|429|403|404)/.test(m.text())) sink.push(`${who}: ${m.text()}`);
  });
  page.on('pageerror', (e) => sink.push(`${who}: ${e.message}`));
}

/** Makes the demo widget act as an already-created contact by seeding the widget's own session storage. */
export async function seedWidgetSession(page: Page, session: Session) {
  const stored = {
    sessionToken: session.token,
    expiresAt: session.expiresAt,
    tenantId: session.tenantId,
    contactId: session.contactId,
    displayName: session.displayName ?? null,
  };
  await page.addInitScript(
    ([key, value]) => {
      if (!localStorage.getItem(key)) localStorage.setItem(key, value);
    },
    [`cp.session.${WIDGET_KEY}`, JSON.stringify(stored)],
  );
}

export interface WidgetHandle {
  widget: Locator;
  log: Locator;
  conversationId: () => Promise<string>;
}

export async function openWidget(page: Page, session: Session): Promise<WidgetHandle> {
  await seedWidgetSession(page, session);
  await page.goto(`${urls.widget}/demo`);
  const widget = page.locator('chat-platform-widget');
  await expect(widget.getByTestId('connection-state')).toHaveText('Live');
  const log = widget.getByTestId('widget-messages');
  await expect(log).toHaveAttribute('data-subscribed', 'true');
  return {
    widget,
    log,
    conversationId: async () => {
      await expect(log).toHaveAttribute('data-conversation-id', /^[0-9a-f-]{36}$/);
      return (await log.getAttribute('data-conversation-id')) as string;
    },
  };
}

export async function widgetSend(widget: Locator, text: string) {
  await widget.getByLabel('Message', { exact: true }).fill(text);
  await widget.getByRole('button', { name: 'Send' }).click();
}

export async function openDashboard(page: Page) {
  await page.goto(`${urls.dashboard}/agent`);
  await expect(page.getByTestId('agent-id')).toHaveText('Demo agent: agentbob');
  await expect(page.getByTestId('connection-state')).toHaveText('Live');
}

export const inboxItem = (page: Page, conversationId: string) =>
  page.locator(`[data-testid="inbox-item"][data-conversation-id="${conversationId}"]`);

export async function openConversation(page: Page, conversationId: string) {
  await page.getByTestId('all-tab').click();
  await inboxItem(page, conversationId).click();
  const log = page.getByTestId('agent-messages');
  await expect(log).toHaveAttribute('data-conversation-id', conversationId);
  await expect(log).toHaveAttribute('data-subscribed', 'true');
  return log;
}

export const agentSend = async (page: Page, text: string) => {
  await page.getByLabel('Reply').fill(text);
  await page.getByRole('button', { name: 'Send' }).click();
};
