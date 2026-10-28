import { expect, test } from '@playwright/test';
import { urls } from '../playwright.config';

test.describe('dashboard', () => {
  test('shows the product title and environment', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle('Chat Platform — Local Development');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Chat Platform — Local Development');
    await expect(page.getByTestId('environment')).toHaveText('local');
  });

  test('shows real healthy backend, database and Centrifugo status', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    page.on('pageerror', (e) => errors.push(e.message));

    await page.goto('/');
    await expect(page.getByText('Backend API: Healthy')).toBeVisible();
    await expect(page.getByText('Database: Healthy')).toBeVisible();
    await expect(page.getByText('Centrifugo: Healthy')).toBeVisible();
    await expect(page.getByText('Allocation: Healthy')).toBeVisible();
    await expect(page.getByTestId('bootstrap-record')).toContainText('local-poc');
    expect(errors, 'browser console errors').toEqual([]);
  });

  test('shows a failure state when the backend is unavailable', async ({ page }) => {
    await page.route(`${urls.api}/**`, (route) => route.abort('connectionrefused'));
    await page.goto('/');
    await expect(page.getByText('Backend API: Unavailable')).toBeVisible();
    await expect(page.getByText('Database: Unavailable')).toBeVisible();
    await expect(page.getByTestId('status-error')).toContainText('Cannot reach the backend');
  });

  test('links to the agent inbox and the widget demo', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('agent-link')).toHaveAttribute('href', '/agent');
    const link = page.getByTestId('widget-demo-link');
    await expect(link).toHaveAttribute('href', `${urls.widget}/demo`);
    const [popup] = await Promise.all([page.waitForEvent('popup'), link.click()]);
    await expect(popup.locator('chat-platform-widget')).toBeVisible();
  });
});
