import { defineConfig } from '@playwright/test';

/**
 * Playwright against the Vite dev server with MSW mocks (VITE_USE_MOCKS=true, set by the
 * `dev` script). Every page load gets fresh mock state, so specs register their own user.
 *
 * Projects: the onboarding flow at 375 and 1280 (BC-0115) and explore screenshots at
 * 320 / 768 / 1024 / 1440 (BC-0116). Baselines live in e2e/__screenshots__/<project>/ and
 * were generated on macOS with headless Chromium; regenerate with --update-snapshots.
 */
const PORT = 4173;
const BASE_URL = `http://127.0.0.1:${PORT}`;

const chromium = {
  browserName: 'chromium' as const,
  colorScheme: 'light' as const,
};

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [['list']],
  snapshotPathTemplate: '{testDir}/__screenshots__/{projectName}/{arg}{ext}',
  use: {
    ...chromium,
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  expect: {
    toHaveScreenshot: {
      animations: 'disabled',
      caret: 'hide',
      maxDiffPixelRatio: 0.01,
    },
  },
  projects: [
    {
      name: 'onboarding-375',
      testMatch: /onboarding\.spec\.ts/,
      use: {
        viewport: { width: 375, height: 812 },
        isMobile: true,
        hasTouch: true,
      },
    },
    {
      name: 'onboarding-1280',
      testMatch: /onboarding\.spec\.ts/,
      use: { viewport: { width: 1280, height: 800 } },
    },
    {
      name: 'explore-320',
      testMatch: /explore-screenshots\.spec\.ts/,
      use: {
        viewport: { width: 320, height: 640 },
        isMobile: true,
        hasTouch: true,
      },
    },
    {
      name: 'explore-768',
      testMatch: /explore-screenshots\.spec\.ts/,
      use: { viewport: { width: 768, height: 1024 } },
    },
    {
      name: 'explore-1024',
      testMatch: /explore-screenshots\.spec\.ts/,
      use: { viewport: { width: 1024, height: 768 } },
    },
    {
      name: 'explore-1440',
      testMatch: /explore-screenshots\.spec\.ts/,
      use: { viewport: { width: 1440, height: 900 } },
    },
  ],
  webServer: {
    command: `pnpm dev --port ${PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: false,
    timeout: 60_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
