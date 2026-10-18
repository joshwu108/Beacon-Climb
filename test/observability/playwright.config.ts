import path from 'node:path';
import { defineConfig } from '@playwright/test';

/**
 * BC-0308: screenshot of the provisioned Grafana dashboard against the RUNNING observability
 * profile (`make observability-up`). Same pattern as test/e2e: apps/web's Playwright install,
 * absolute paths, no webServer, no mocks.
 *
 *   GRAFANA_ADMIN_PASSWORD=... pnpm --filter @beacon/web exec playwright test \
 *     --config ../../test/observability/playwright.config.ts
 *
 * Environment: GRAFANA_URL (default http://127.0.0.1:3001), GRAFANA_ADMIN_PASSWORD (required,
 * read from .env by the caller, never printed), GRAFANA_SCREENSHOT_PATH (default
 * docs/evidence/BC-0308/dashboard.png, relative to the repo root).
 */
const BASE_URL = process.env.GRAFANA_URL ?? 'http://127.0.0.1:3001';

export default defineConfig({
  testDir: __dirname,
  testMatch: /grafana-screenshot\.ts$/,
  outputDir: path.join(__dirname, 'test-results'),
  fullyParallel: false,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 30_000 },
  reporter: [['list']],
  use: {
    baseURL: BASE_URL,
    browserName: 'chromium',
    colorScheme: 'dark',
    timezoneId: 'UTC',
    // Tall enough for every row of the provisioned dashboard: Grafana only mounts and queries
    // panels that are inside the viewport (lazy loading), so a short page screenshots empty panels.
    viewport: { width: 1600, height: 2400 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
