import path from 'node:path';
import { defineConfig } from '@playwright/test';

/**
 * BC-0121: the vertical slice against the REAL compose stack (`make dev && make seed-demo`):
 * nginx on 127.0.0.1:3000 serves the built SPA and proxies /v1 and /health to the api, so
 * there is no webServer here and no mocks anywhere. Run with `make test-e2e`, which uses
 * apps/web's Playwright install:
 *
 *   pnpm --filter @beacon/web exec playwright test --config ../../test/e2e/playwright.config.ts
 *
 * Paths are absolute (`__dirname`) because the config is loaded from another package.
 * Every spec registers its own user (`e2e_<timestamp>_...`), so the suite can run again and
 * again on the same database. Trace and video are always on: a green run's artifacts under
 * test/e2e/test-results are the Gate G1 evidence (planning/06 section 13).
 */
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3000';

export default defineConfig({
  testDir: __dirname,
  outputDir: path.join(__dirname, 'test-results'),
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  // One worker per project: the two journeys use different users and never share state.
  workers: 2,
  timeout: 240_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    [
      'html',
      {
        open: 'never',
        outputFolder: path.join(__dirname, 'playwright-report'),
      },
    ],
  ],
  use: {
    baseURL: BASE_URL,
    browserName: 'chromium',
    colorScheme: 'light',
    timezoneId: 'UTC',
    trace: 'on',
    video: 'on',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium-desktop',
      use: { viewport: { width: 1280, height: 800 } },
    },
    {
      name: 'chromium-mobile',
      use: {
        viewport: { width: 375, height: 812 },
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
});
