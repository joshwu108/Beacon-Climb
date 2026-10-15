import { expect, test } from '@playwright/test';

/**
 * BC-0116 visual regression: /explore and one route detail at 320 / 768 / 1024 / 1440
 * (playwright.config.ts projects). A session is seeded into sessionStorage before the
 * app boots; the mocks answer GET /v1/me with the fixture (board synthetic-demo-1, 35°).
 * Baselines: e2e/__screenshots__/<project>/, macOS headless Chromium, viewport only
 * (fullPage: false) to keep the PNGs small.
 */

const SESSION_STORAGE_KEY = 'beacon.session.v1';
const SESSION = JSON.stringify({
  token: 'opaque-demo-token',
  expiresAt: '2999-01-01T00:00:00Z',
});

test.beforeEach(async ({ page }) => {
  await page.addInitScript(
    ([key, value]) => sessionStorage.setItem(key, value),
    [SESSION_STORAGE_KEY, SESSION],
  );
});

test('explore index', async ({ page }) => {
  await page.goto('/explore');
  await expect(
    page.getByText('10 routes loaded, more available'),
  ).toBeVisible();
  await expect(
    page.getByText('Synthetic Traverse 1', { exact: true }),
  ).toBeVisible();
  await expect(page).toHaveScreenshot('explore.png', { fullPage: false });
});

test('route detail', async ({ page }) => {
  await page.goto('/explore/synthetic-route-001%3A35');
  await expect(
    page.getByRole('heading', { level: 1, name: 'Synthetic Traverse 1' }),
  ).toBeVisible();
  await expect(
    page.getByRole('img', { name: /route synthetic traverse 1 uses 3/i }),
  ).toBeVisible();
  await expect(
    page.getByText('Style tags: not available for this route.'),
  ).toBeVisible();
  await expect(page).toHaveScreenshot('route-detail.png', { fullPage: false });
});
