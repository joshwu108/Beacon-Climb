import { defineConfig } from '@playwright/test';

/**
 * Smoke test against the exported Expo web build (react-native-web) in mock mode: the
 * webServer exports with EXPO_PUBLIC_USE_MOCKS=true and serves dist/ with `expo serve`.
 * Every page load starts with fresh in-process mock state, so the spec registers its own
 * user. Two projects: the laptop demo (phone frame) and a phone-width browser (no frame).
 */
const PORT = 8090;
const BASE_URL = `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
  ],
  use: {
    baseURL: BASE_URL,
    browserName: 'chromium',
    colorScheme: 'light',
    timezoneId: 'UTC',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'laptop-frame', use: { viewport: { width: 1280, height: 900 } } },
    {
      name: 'phone-390',
      use: {
        viewport: { width: 390, height: 844 },
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
  webServer: {
    command: `EXPO_PUBLIC_USE_MOCKS=true pnpm export:web && pnpm exec expo serve --port ${PORT}`,
    url: BASE_URL,
    reuseExistingServer: false,
    timeout: 240_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
