import { defineConfig, type PlaywrightTestProject } from '@playwright/test';

/**
 * Playwright against the Vite dev server with MSW mocks (VITE_USE_MOCKS=true, set by the
 * `dev` script). Every page load gets fresh mock state, so specs register their own user
 * or seed a session (e2e/states.ts).
 *
 * Projects:
 * - Flow specs (BC-0115, BC-0117, BC-0118) at 375 and 1280: onboarding-*, session-*,
 *   attempts-*.
 * - Quality bar (BC-0119): bp-320 / bp-375 / bp-768 / bp-1024 / bp-1440 / bp-1920 run
 *   a11y.spec.ts (axe, serious/critical = 0) and responsive.spec.ts (no horizontal
 *   overflow, main landmark visible, viewport screenshots at 320 / 768 / 1024 / 1440);
 *   `keyboard` (1024) runs keyboard.spec.ts; `reduced-motion` (1024, prefers-reduced-motion)
 *   runs reduced-motion.spec.ts.
 *
 * Baselines live in e2e/__screenshots__/<project>/ and were generated on macOS with
 * headless Chromium; regenerate with --update-snapshots. Screenshot assertions only run on
 * darwin until Linux baselines exist (see README "Playwright suite").
 */
const PORT = 4173;
const BASE_URL = `http://127.0.0.1:${PORT}`;

const chromium = {
  browserName: 'chromium' as const,
  colorScheme: 'light' as const,
  // Fixture timestamps render as local time; pin the zone so baselines match everywhere.
  timezoneId: 'UTC',
};

const phone = {
  viewport: { width: 375, height: 812 },
  isMobile: true,
  hasTouch: true,
};
const laptop = { viewport: { width: 1280, height: 800 } };

/** The six breakpoints from the web testing rules. Phones get touch + mobile emulation. */
export const BREAKPOINTS = [
  { width: 320, height: 640, mobile: true },
  { width: 375, height: 812, mobile: true },
  { width: 768, height: 1024, mobile: false },
  { width: 1024, height: 768, mobile: false },
  { width: 1440, height: 900, mobile: false },
  { width: 1920, height: 1080, mobile: false },
] as const;

const QUALITY_SPECS = /(a11y|responsive)\.spec\.ts/;

const breakpointProjects: PlaywrightTestProject[] = BREAKPOINTS.map((bp) => ({
  name: `bp-${bp.width}`,
  testMatch: QUALITY_SPECS,
  use: {
    viewport: { width: bp.width, height: bp.height },
    isMobile: bp.mobile,
    hasTouch: bp.mobile,
  },
}));

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
  ],
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
    { name: 'onboarding-375', testMatch: /onboarding\.spec\.ts/, use: phone },
    { name: 'onboarding-1280', testMatch: /onboarding\.spec\.ts/, use: laptop },
    { name: 'session-375', testMatch: /session\.spec\.ts/, use: phone },
    { name: 'session-1280', testMatch: /session\.spec\.ts/, use: laptop },
    { name: 'attempts-375', testMatch: /attempts\.spec\.ts/, use: phone },
    { name: 'attempts-1280', testMatch: /attempts\.spec\.ts/, use: laptop },
    ...breakpointProjects,
    {
      name: 'keyboard',
      testMatch: /keyboard\.spec\.ts/,
      use: { viewport: { width: 1024, height: 768 } },
    },
    {
      name: 'reduced-motion',
      testMatch: /reduced-motion\.spec\.ts/,
      use: {
        viewport: { width: 1024, height: 768 },
        contextOptions: { reducedMotion: 'reduce' },
      },
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
