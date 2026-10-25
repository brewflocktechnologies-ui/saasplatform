import { expect, test } from '@playwright/test';
import { urls } from '../playwright.config';

test.describe('widget app', () => {
  test('overview page renders the standalone web-component badge and links to /demo', async ({ page }) => {
    await page.goto(urls.widget);
    await expect(page.getByTestId('standalone-badge').getByRole('status')).toHaveText(/Widget bundle:\s*Healthy/);
    await expect(page.getByTestId('demo-link')).toHaveAttribute('href', '/demo');
  });

  test('demo page renders the custom element as userone with a POC notice', async ({ page }) => {
    await page.goto(`${urls.widget}/demo`);
    await expect(page.getByRole('note')).toContainText('LOCAL POC — NOT REAL AUTHENTICATION');
    const widget = page.locator('chat-platform-widget');
    await expect(widget.getByTestId('visitor-id')).toHaveText('Demo visitor: userone');
    await expect(widget.getByTestId('connection-state')).toHaveText('Live');
  });

  test('blank messages are not sent', async ({ page }) => {
    await page.goto(`${urls.widget}/demo`);
    const widget = page.locator('chat-platform-widget');
    await expect(widget.getByTestId('connection-state')).toHaveText('Live');
    const before = await widget.getByTestId('message').count();
    await widget.getByLabel('Message', { exact: true }).fill('    ');
    await widget.getByRole('button', { name: 'Send' }).click();
    await expect(widget.getByTestId('widget-notice')).toContainText('Type a message first');
    await expect(widget.getByTestId('message')).toHaveCount(before);
  });

  test('design tokens from the document reach shadow DOM styles', async ({ page }) => {
    await page.goto(`${urls.widget}/demo`);
    await expect(page.locator('chat-platform-widget').getByTestId('visitor-id')).toBeVisible();
    const { token, headerBg } = await page.evaluate(() => {
      const probe = document.createElement('div');
      probe.style.background = 'var(--cp-color-primary)';
      document.body.append(probe);
      const token = getComputedStyle(probe).backgroundColor;
      probe.remove();
      const header = document.querySelector('chat-platform-widget')?.shadowRoot?.querySelector('header');
      return { token, headerBg: header ? getComputedStyle(header).backgroundColor : null };
    });
    expect(token).not.toBe('rgba(0, 0, 0, 0)');
    expect(headerBg).toBe(token);
  });

  test('has no console errors', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto(`${urls.widget}/demo`);
    await expect(page.locator('chat-platform-widget').getByTestId('connection-state')).toHaveText('Live');
    expect(errors).toEqual([]);
  });
});
