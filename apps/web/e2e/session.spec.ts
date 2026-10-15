import { expect, test, type Page } from '@playwright/test';

/**
 * BC-0117 acceptance at 375 and 1280 (playwright.config.ts projects): a seeded returning
 * user resumes past onboarding, builds a session, dismisses a route (it is gone from the
 * re-requested lineup) and opens the decision record. Mock state is per page load.
 */

const SESSION_STORAGE_KEY = 'beacon.session.v1';
const SESSION = JSON.stringify({
  token: 'opaque-demo-token',
  expiresAt: '2999-01-01T00:00:00Z',
});

const cardNames = async (page: Page): Promise<string[]> =>
  page
    .getByRole('list', { name: 'Recommended routes in order' })
    .locator(':scope > li')
    .getByRole('heading', { level: 3 })
    .allTextContents();

test.beforeEach(async ({ page }) => {
  await page.addInitScript(
    ([key, value]) => sessionStorage.setItem(key, value),
    [SESSION_STORAGE_KEY, SESSION],
  );
});

test('onboarding resume -> session -> dismiss a route -> decision detail', async ({
  page,
}) => {
  await page.goto('/onboarding');
  await expect(page).toHaveURL(/\/explore$/);

  await page.getByRole('link', { name: 'Session' }).click();
  await expect(page).toHaveURL(/\/session\?session_id=[0-9a-f-]{36}/);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Build a session' }),
  ).toBeVisible();
  const sessionId = new URL(page.url()).searchParams.get('session_id');
  await expect(page.getByRole('radio', { name: /technique/i })).toBeChecked();

  await page.getByRole('button', { name: 'Build my session' }).click();
  await expect(
    page.getByRole('heading', { level: 2, name: /your session: 4 routes/i }),
  ).toBeVisible();
  await expect(
    page.getByText(/recommendations are heuristic until you log/i),
  ).toBeVisible();
  const before = await cardNames(page);
  expect(before).toHaveLength(4);
  await expect(page.getByText('No personalised estimate yet')).toHaveCount(4);

  const second = page
    .getByRole('list', { name: 'Recommended routes in order' })
    .locator(':scope > li')
    .nth(1);
  const dismissed = await second
    .getByRole('heading', { level: 3 })
    .textContent();
  await second.getByRole('button', { name: 'Dismiss' }).click();
  await expect(
    page.getByRole('heading', { level: 2, name: /your session: 3 routes/i }),
  ).toBeVisible();
  const after = await cardNames(page);
  expect(after).toHaveLength(3);
  expect(after).not.toContain(dismissed);
  await expect(page.getByText(/dismissed: 1 route/i)).toBeVisible();
  expect(new URL(page.url()).searchParams.get('session_id')).toBe(sessionId);

  await page.getByRole('link', { name: 'Why these routes' }).click();
  await expect(page).toHaveURL(/\/decisions\/[0-9a-f-]{36}$/);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Why these routes' }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', {
      level: 2,
      name: 'All routes in this list (3)',
    }),
  ).toBeVisible();
  const about = page.getByRole('complementary', { name: 'About this list' });
  await expect(about.getByText('heuristic-v1')).toBeVisible();
  await expect(about.getByText('Technique, up to 3 routes')).toBeVisible();
  await expect(
    page.getByText('Matches your board and angle').first(),
  ).toBeVisible();
  await expect(
    page
      .getByText('This route exists on your board at your selected angle.')
      .first(),
  ).toBeVisible();
});
