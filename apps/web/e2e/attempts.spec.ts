import { expect, test, type Page } from '@playwright/test';

/**
 * BC-0118 acceptance at 375 and 1280: a double-clicked Save yields one history row, a
 * correction bumps the revision, a concurrent change elsewhere triggers the refresh
 * prompt, and an attempt saved while offline syncs once the browser is back online.
 * Mock state lives in the page (MSW worker), so "elsewhere" is a direct fetch from the
 * page and offline is Playwright's emulation, which the mocks honour via navigator.onLine.
 */

const SESSION_STORAGE_KEY = 'beacon.session.v1';
const SESSION = JSON.stringify({
  token: 'opaque-demo-token',
  expiresAt: '2999-01-01T00:00:00Z',
});
const FIXTURE_ID = '018f0000-0000-7000-8000-000000000002';
const FORM = '/attempts/new?route_variant_id=synthetic-route-002%3A35';

const historyRows = (page: Page) =>
  page.getByRole('region', { name: 'History' }).getByRole('listitem');

test.beforeEach(async ({ page }) => {
  await page.addInitScript(
    ([key, value]) => sessionStorage.setItem(key, value),
    [SESSION_STORAGE_KEY, SESSION],
  );
});

test('a double-clicked Save logs one attempt; Correct makes revision 2', async ({
  page,
}) => {
  await page.goto(FORM);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Log an attempt' }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 2, name: /synthetic/i }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'One attempt more' }).click();
  await page.getByRole('switch', { name: /sent/i }).check();
  await page.getByLabel(/notes/i).fill('Two goes, sent the second.');
  await page.getByRole('button', { name: 'Save attempt' }).dblclick();

  await expect(page).toHaveURL(/\/attempts$/);
  await expect(page.getByText('Saved as revision 1.')).toBeVisible();
  await expect(page.getByText('2 attempts loaded')).toBeVisible();
  await expect(historyRows(page)).toHaveCount(2);
  const row = historyRows(page).first();
  await expect(row.getByText('Two goes, sent the second.')).toBeVisible();
  await expect(row.getByText('Revision 1', { exact: true })).toBeVisible();

  await row.getByRole('button', { name: 'Correct' }).click();
  await row.getByRole('button', { name: 'One attempt more' }).click();
  await row.getByRole('button', { name: 'Save correction' }).click();
  await expect(row.getByText('Revision 2', { exact: true })).toBeVisible();
  await expect(row.getByText('Corrected')).toBeVisible();
  await expect(row.getByText('3 attempts')).toBeVisible();
});

test('a change made elsewhere shows the refresh prompt with the current revision', async ({
  page,
}) => {
  await page.goto('/attempts');
  const row = historyRows(page).filter({ hasText: 'Synthetic Traverse 1' });
  await expect(row.getByText('Revision 1', { exact: true })).toBeVisible();

  // Another client corrects the same attempt against the in-page mocks.
  const status = await page.evaluate(async (id) => {
    const res = await fetch(`http://127.0.0.1:8080/v1/me/attempts/${id}`, {
      method: 'PATCH',
      headers: {
        Authorization: 'Bearer opaque-demo-token',
        'Content-Type': 'application/json',
        'If-Match': '"1"',
      },
      body: JSON.stringify({ perceived_effort: 8 }),
    });
    return res.status;
  }, FIXTURE_ID);
  expect(status).toBe(200);

  await row.getByRole('button', { name: 'Correct' }).click();
  await row.getByRole('button', { name: 'One attempt more' }).click();
  await row.getByRole('button', { name: 'Save correction' }).click();
  const alert = row.getByRole('alert');
  await expect(alert).toContainText(
    'This attempt changed elsewhere (revision 2). Refresh',
  );
  await alert.getByRole('button', { name: 'Refresh' }).click();
  await expect(row.getByText('Revision 2', { exact: true })).toBeVisible();
  await expect(row.getByText('Effort 8/10')).toBeVisible();
  await row.getByRole('button', { name: 'One attempt more' }).click();
  await row.getByRole('button', { name: 'Save correction' }).click();
  await expect(row.getByText('Revision 3', { exact: true })).toBeVisible();
});

test('an attempt saved while offline syncs once the browser is back online', async ({
  page,
  context,
}) => {
  await page.goto(FORM);
  await expect(
    page.getByRole('heading', { level: 2, name: /synthetic/i }),
  ).toBeVisible();
  await context.setOffline(true);
  await page.getByRole('button', { name: 'Save attempt' }).click();
  await expect(page.getByRole('alert')).toContainText(
    "We didn't get a reply, so we can't tell if it saved.",
  );
  await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();

  await context.setOffline(false);
  // The reconnect sync sends the draft with its stored key, then the log shows it.
  await expect(page).toHaveURL(/\/attempts$/, { timeout: 15_000 });
  await expect(page.getByText('2 attempts loaded')).toBeVisible();
  await expect(
    page.getByRole('region', { name: /waiting to save/i }),
  ).toHaveCount(0);
});
