import { expect, test } from '@playwright/test';
import { urls } from '../playwright.config';

test.describe('widget demo', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(urls.widget);
  });

  test('renders the custom element and greeting', async ({ page }) => {
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Local POC');
    const widget = page.locator('chat-platform-widget');
    await expect(widget).toBeVisible();
    // Playwright locators pierce open shadow roots.
    await expect(widget.getByTestId('greeting')).toContainText('How can we help');
  });

  test('sending a message updates the in-memory message list', async ({ page }) => {
    const widget = page.locator('chat-platform-widget');
    await widget.getByLabel('Message', { exact: true }).fill('Hello from Playwright');
    await widget.getByRole('button', { name: 'Send' }).click();
    await expect(widget.getByTestId('message')).toHaveText(['Hello from Playwright']);
    await expect(widget.getByLabel('Message', { exact: true })).toHaveValue('');
  });

  test('renders the standalone web-component library badge', async ({ page }) => {
    await expect(page.getByTestId('standalone-badge').getByRole('status')).toHaveText(/Widget bundle:\s*Healthy/);
  });

  test('design tokens from the document reach shadow DOM styles', async ({ page }) => {
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
    await page.reload();
    await expect(page.locator('chat-platform-widget')).toBeVisible();
    expect(errors).toEqual([]);
  });
});
