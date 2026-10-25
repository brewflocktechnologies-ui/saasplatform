import { defineConfig, devices } from '@playwright/test';

/**
 * Smoke E2E + live contract tests.
 * Prerequisite: infrastructure and backend running (Mode A or Mode B, see docs/setup/local-development.md).
 * Frontends: reused if already running on their ports (dev servers or containers); otherwise Playwright starts
 * production builds via Nx (`chatdashboardapp:start`, `chatwidget:preview`).
 */
export const urls = {
  dashboard: process.env.DASHBOARD_URL ?? 'http://localhost:3000',
  widget: process.env.WIDGET_URL ?? 'http://localhost:5173',
  api: process.env.API_URL ?? 'http://localhost:8080',
};

export default defineConfig({
  testDir: '.',
  testMatch: ['e2e/**/*.spec.ts', 'contract/**/*.spec.ts'],
  outputDir: '../dist/.playwright/tests/test-output',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { outputFolder: '../dist/.playwright/tests/report', open: 'never' }]],
  use: {
    baseURL: urls.dashboard,
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'pnpm nx run chatdashboardapp:start',
      url: urls.dashboard,
      cwd: '..',
      reuseExistingServer: true,
      timeout: 240_000,
    },
    {
      command: 'pnpm nx run chatwidget:preview',
      url: urls.widget,
      cwd: '..',
      reuseExistingServer: true,
      timeout: 120_000,
    },
  ],
});
