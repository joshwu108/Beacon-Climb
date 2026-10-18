import { expect, test, type Page } from '@playwright/test';
import { DRAFT_DB_NAME, DRAFT_STORE_NAME } from './states';

/**
 * BC-0118 acceptance at 375 and 1280: a double-clicked Save yields one history row, a
 * correction bumps the revision, a concurrent change elsewhere triggers the refresh
 * prompt, and an attempt saved while offline syncs once the browser is back online.
 * Mock state lives in the page (MSW worker), so "elsewhere" is a direct fetch from the
 * page and offline is Playwright's emulation, which the mocks honour via navigator.onLine.
 * The BC-0123 F4 repro: a draft left by one account on a shared browser is neither shown
 * to nor sent under the next account. The BC-0123 N4 pair: signing in again as the same
 * account in another tab keeps this tab's unsent draft; signing in as another account
 * removes it.
 */

const SESSION_STORAGE_KEY = 'beacon.session.v1';
const SESSION = JSON.stringify({
  token: 'opaque-demo-token',
  expiresAt: '2999-01-01T00:00:00Z',
});
const FIXTURE_ID = '018f0000-0000-7000-8000-000000000002';
const FORM = '/attempts/new?route_variant_id=synthetic-route-002%3A40';

const historyRows = (page: Page) =>
  page.getByRole('region', { name: 'History' }).getByRole('listitem');

/** Draft rows in IndexedDB, read directly: what the next page load will find. */
const draftsOnDevice = (p: Page): Promise<number> =>
  p.evaluate(
    ([dbName, storeName]) =>
      new Promise<number>((resolve, reject) => {
        const open = indexedDB.open(dbName, 1);
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const count = open.result
            .transaction(storeName, 'readonly')
            .objectStore(storeName)
            .count();
          count.onsuccess = () => {
            open.result.close();
            resolve(count.result);
          };
          count.onerror = () => reject(count.error);
        };
      }),
    [DRAFT_DB_NAME, DRAFT_STORE_NAME] as const,
  );

/** The notes of every draft row in IndexedDB. */
const draftNotesOnDevice = (p: Page): Promise<string[]> =>
  p.evaluate(
    ([dbName, storeName]) =>
      new Promise<string[]>((resolve, reject) => {
        const open = indexedDB.open(dbName, 1);
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const all = open.result
            .transaction(storeName, 'readonly')
            .objectStore(storeName)
            .getAll();
          all.onsuccess = () => {
            open.result.close();
            resolve(
              (all.result as { body: { notes?: string } }[]).map(
                (row) => row.body.notes ?? '',
              ),
            );
          };
          all.onerror = () => reject(all.error);
        };
      }),
    [DRAFT_DB_NAME, DRAFT_STORE_NAME] as const,
  );

const OFFLINE_FLAG = 'e2e.offline';

/**
 * The seeded session with the GET /v1/me fixture user already recorded, as the token
 * store itself writes it after /v1/me answers. The shared beforeEach re-seeds the
 * session on every load, and an offline reload cannot ask /v1/me again, so the tab needs
 * its user id in the seed to list its own drafts.
 */
const FIXTURE_USER_ID = '018f0000-0000-7000-8000-000000000001';
const SESSION_WITH_USER = JSON.stringify({
  ...(JSON.parse(SESSION) as Record<string, string>),
  userId: FIXTURE_USER_ID,
});

/**
 * Offline for one tab only. The mocks answer with a network error while
 * `navigator.onLine` is false, so a getter backed by this tab's sessionStorage keeps the
 * tab offline across reloads while other tabs in the context stay online.
 * (`context.setOffline(false)` would fire `online` here and auto-sync the draft.)
 */
const emulateOfflinePerTab = (p: Page): Promise<void> =>
  p.addInitScript((flag) => {
    Object.defineProperty(navigator, 'onLine', {
      configurable: true,
      get: () => sessionStorage.getItem(flag) !== '1',
    });
  }, OFFLINE_FLAG);

const goOfflineInTab = (p: Page): Promise<void> =>
  p.evaluate((flag) => sessionStorage.setItem(flag, '1'), OFFLINE_FLAG);

/** User U (the seeded demo session) saves an attempt that cannot reach the server. */
const leaveUnsentDraft = async (p: Page, notes: string): Promise<void> => {
  await p.addInitScript(
    ([key, value]) => sessionStorage.setItem(key, value),
    [SESSION_STORAGE_KEY, SESSION_WITH_USER],
  );
  await emulateOfflinePerTab(p);
  await p.goto(FORM);
  await expect(
    p.getByRole('heading', { level: 2, name: /synthetic/i }),
  ).toBeVisible();
  await expect(p.getByRole('button', { name: 'Save attempt' })).toBeEnabled();
  await goOfflineInTab(p);
  await p.getByLabel(/notes/i).fill(notes);
  await p.getByRole('button', { name: 'Save attempt' }).click();
  await expect(p.getByRole('alert')).toContainText(
    "We didn't get a reply, so we can't tell if it saved.",
  );
  expect(await draftNotesOnDevice(p)).toEqual([notes]);
};

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

test('a draft left by one account is neither shown to nor sent under the next account (BC-0123 F4)', async ({
  page,
  context,
}) => {
  // User A (the seeded demo session) logs an attempt while offline and never signs out.
  await page.goto(FORM);
  await expect(
    page.getByRole('heading', { level: 2, name: /synthetic/i }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Save attempt' }),
  ).toBeEnabled();
  await context.setOffline(true);
  await page.getByLabel(/notes/i).fill('A-private');
  await page.getByRole('button', { name: 'Save attempt' }).click();
  await expect(page.getByRole('alert')).toContainText(
    "We didn't get a reply, so we can't tell if it saved.",
  );
  expect(await draftsOnDevice(page)).toBe(1);
  await page.close();

  // User B registers in a new page of the same browser (no session, same IndexedDB).
  const next = await context.newPage();
  const seen: { method: string; path: string; body: string }[] = [];
  next.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/v1/'))
      seen.push({
        method: request.method(),
        path: url.pathname,
        body: request.postData() ?? '',
      });
  });
  await context.setOffline(false);
  await next.goto('/onboarding');
  await next
    .getByRole('textbox', { name: 'Username' })
    .fill(`user_b_${Date.now().toString(36)}`);
  await next.getByLabel('Password', { exact: true }).fill('a-long-password');
  await next.getByRole('button', { name: 'Create account' }).click();
  await expect(
    next.getByRole('heading', {
      level: 2,
      name: /decide what beacon may keep/i,
    }),
  ).toBeVisible();

  await next.goto('/attempts');
  await expect(next.getByText('1 attempt loaded')).toBeVisible();
  await expect(
    next.getByRole('region', { name: /waiting to save/i }),
  ).toHaveCount(0);
  await expect(next.getByText('A-private')).toHaveCount(0);
  // A's draft was purged when GET /v1/me identified B, and nothing was sent for it.
  await expect.poll(() => draftsOnDevice(next)).toBe(0);
  expect(seen.some((r) => r.path === '/v1/me/attempts')).toBe(true);
  expect(
    seen.filter((r) => r.method === 'POST' && r.path === '/v1/me/attempts'),
  ).toEqual([]);
  expect(seen.some((r) => r.body.includes('A-private'))).toBe(false);
  await next.close();
});

test("a re-login as the same account in another tab keeps this tab's unsent draft (BC-0123 N4)", async ({
  page,
  context,
}) => {
  await leaveUnsentDraft(page, 'U-keeps-this');

  // The same person signs in again in a second tab: fresh sessionStorage, same IndexedDB.
  const other = await context.newPage();
  await other.goto('/onboarding');
  await other.getByRole('button', { name: 'Sign in instead' }).click();
  await other.getByRole('textbox', { name: 'Username' }).fill('demo');
  await other.getByLabel('Password', { exact: true }).fill('demo-password');
  await other.getByRole('button', { name: 'Sign in' }).click();
  // Landing on /explore means GET /v1/me answered, so the sign-in purge has run.
  await expect(other).toHaveURL(/\/explore$/);
  expect(await draftNotesOnDevice(other)).toEqual(['U-keeps-this']);
  await other.close();

  // Still offline in the first tab, the draft is there after a reload, waiting to be sent.
  await page.goto('/attempts');
  await expect(
    page.getByRole('heading', { level: 2, name: 'Waiting to save (1)' }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
  expect(await draftNotesOnDevice(page)).toEqual(['U-keeps-this']);
});

test("signing in as another account in a second tab removes this tab's unsent draft (BC-0123 N4)", async ({
  page,
  context,
}) => {
  await leaveUnsentDraft(page, 'U-private');

  // Someone else registers in a second tab of the same browser.
  const other = await context.newPage();
  await other.goto('/onboarding');
  await other
    .getByRole('textbox', { name: 'Username' })
    .fill(`user_v_${Date.now().toString(36)}`);
  await other.getByLabel('Password', { exact: true }).fill('a-long-password');
  await other.getByRole('button', { name: 'Create account' }).click();
  await expect(
    other.getByRole('heading', {
      level: 2,
      name: /decide what beacon may keep/i,
    }),
  ).toBeVisible();
  // GET /v1/me identified V: U's draft left the device.
  await expect.poll(() => draftsOnDevice(other)).toBe(0);
  await other.close();

  await page.goto('/attempts');
  await expect(
    page.getByRole('heading', { level: 1, name: 'Attempt log' }),
  ).toBeVisible();
  await expect(
    page.getByRole('region', { name: /waiting to save/i }),
  ).toHaveCount(0);
  expect(await draftsOnDevice(page)).toBe(0);
});
