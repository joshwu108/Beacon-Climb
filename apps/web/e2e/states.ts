import { expect, type Page } from '@playwright/test';

/**
 * BC-0119 shared page states. Every state is a function that drives the app (against the
 * in-page MSW mocks) into one meaningful screen from docs/ux/journeys.md and waits for it,
 * so the a11y, responsive, keyboard and reduced-motion specs scan the same thing. States
 * are deterministic: a fixed session id (the mock lineup is rotated by its hash), the
 * fixture decision id, a fixed clock on the attempt form and a seeded IndexedDB draft.
 */

export const SESSION_STORAGE_KEY = 'beacon.session.v1';
export const SESSION = JSON.stringify({
  token: 'opaque-demo-token',
  expiresAt: '2999-01-01T00:00:00Z',
});

export const FIXED_SESSION_ID = '018f0000-0000-7000-8000-00000000e2e1';
export const FIXTURE_DECISION_ID = '018f0000-0000-7000-8000-000000000003';
export const FIXTURE_ROUTE_PATH = '/explore/synthetic-route-001%3A35';
export const ATTEMPT_FORM_PATH =
  '/attempts/new?route_variant_id=synthetic-route-002%3A35';
export const SESSION_PATH = `/session?session_id=${FIXED_SESSION_ID}&goal=TECHNIQUE&routes=4`;
/** Fixed "now" for the attempt form so its default `occurred_at` is stable in baselines. */
export const FIXED_NOW = '2026-10-15T12:00:00Z';
export const DRAFT_DB_NAME = 'beacon.drafts.v1';
export const DRAFT_STORE_NAME = 'drafts';

/** A signed-in returning user with consent and preferences (the GET /v1/me fixture). */
export const seedSession = (page: Page): Promise<void> =>
  page.addInitScript(
    ([key, value]) => sessionStorage.setItem(key, value),
    [SESSION_STORAGE_KEY, SESSION],
  );

const stepHeading = (page: Page, name: RegExp) =>
  page.getByRole('heading', { level: 2, name });

export type OnboardingStep = 'account' | 'consent' | 'board' | 'goals';

/** Walk the first-run flow with a fresh username (mock state is per page load) up to `step`. */
export const walkOnboarding = async (
  page: Page,
  step: OnboardingStep,
): Promise<void> => {
  await page.goto('/onboarding');
  await expect(stepHeading(page, /choose a username/i)).toBeVisible();
  if (step === 'account') return;

  await page
    .getByRole('textbox', { name: 'Username' })
    .fill(`e2e_${Date.now().toString(36)}`);
  await page.getByLabel('Password', { exact: true }).fill('a-long-password');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(stepHeading(page, /decide what beacon may keep/i)).toBeVisible();
  await expect(page.getByText('Beacon Climb consent v1')).toBeVisible();
  if (step === 'consent') return;

  await page
    .getByRole('checkbox', { name: /i agree to the service use/i })
    .check();
  await page.getByRole('button', { name: /record my choice/i }).click();
  await expect(stepHeading(page, /which board do you climb on/i)).toBeVisible();
  await expect(page.getByRole('radio', { name: /synthetic/i })).toBeChecked();
  if (step === 'board') return;

  await page.getByRole('radio', { name: '45°' }).check();
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(stepHeading(page, /where to start/i)).toBeVisible();
};

export const openExplore = async (page: Page): Promise<void> => {
  await seedSession(page);
  await page.goto('/explore');
  await expect(
    page.getByText('10 routes loaded, more available'),
  ).toBeVisible();
  await expect(
    page.getByText('Synthetic Traverse 1', { exact: true }),
  ).toBeVisible();
};

export const openRouteDetail = async (page: Page): Promise<void> => {
  await seedSession(page);
  await page.goto(FIXTURE_ROUTE_PATH);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Synthetic Traverse 1' }),
  ).toBeVisible();
  await expect(
    page.getByRole('img', { name: /route synthetic traverse 1 uses 3/i }),
  ).toBeVisible();
};

export const openSessionBuilder = async (page: Page): Promise<void> => {
  await seedSession(page);
  await page.goto(SESSION_PATH);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Build a session' }),
  ).toBeVisible();
  await expect(page.getByRole('radio', { name: /technique/i })).toBeChecked();
  await expect(
    page.getByRole('button', { name: 'Build my session' }),
  ).toBeEnabled();
};

export const lineupHeading = (page: Page) =>
  page.getByRole('heading', { level: 2, name: /your session: 4 routes/i });

export const openSessionLineup = async (page: Page): Promise<void> => {
  await openSessionBuilder(page);
  await page.getByRole('button', { name: 'Build my session' }).click();
  await expect(lineupHeading(page)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Dismiss' })).toHaveCount(4);
};

export const openDecision = async (page: Page): Promise<void> => {
  await seedSession(page);
  await page.goto(`/decisions/${FIXTURE_DECISION_ID}`);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Why these routes' }),
  ).toBeVisible();
  await expect(
    page.getByRole('complementary', { name: 'About this list' }),
  ).toBeVisible();
};

/**
 * A draft that will not auto-sync: `failed` with a 409 IDEMPOTENCY_CONFLICT is "Needs
 * attention" (not retryable), so it stays on the device next to the server rows.
 */
const SEEDED_DRAFT = {
  draft_id: '018f0000-0000-7000-8000-00000000d4a1',
  idempotency_key: '018f0000-0000-7000-8000-00000000d4a2',
  /** The GET /v1/me fixture user: drafts of other owners are never shown (BC-0123 F4). */
  owner_user_id: '018f0000-0000-7000-8000-000000000001',
  body: {
    route_variant_id: 'synthetic-route-003:35',
    occurred_at: '2026-10-15T09:30:00Z',
    attempts_count: 2,
    sent: false,
    completion_fraction: 0.6,
    perceived_effort: 7,
    notes: 'Fell at the last move twice.',
  },
  state: 'failed',
  attempt: null,
  error: {
    status: 409,
    code: 'IDEMPOTENCY_CONFLICT',
    message: 'Request cannot be applied',
    request_id: 'req-e2e-draft',
    field_errors: [],
  },
  created_at: '2026-10-15T09:31:00Z',
  route_name: 'Synthetic Traverse 3',
};

export const historyRows = (page: Page) =>
  page.getByRole('region', { name: 'History' }).getByRole('listitem');

export const openAttemptsHistory = async (page: Page): Promise<void> => {
  await seedSession(page);
  await page.goto('/attempts');
  await expect(page.getByText('1 attempt loaded')).toBeVisible();
  await expect(historyRows(page)).toHaveCount(1);
};

/** Attempts history with the fixture rows and one draft waiting on the device. */
export const openAttemptsHistoryWithDraft = async (
  page: Page,
): Promise<void> => {
  await openAttemptsHistory(page);
  await page.evaluate(
    ([dbName, draft]) =>
      new Promise<void>((resolve, reject) => {
        const open = indexedDB.open(dbName, 1);
        open.onupgradeneeded = () =>
          open.result.createObjectStore('drafts', { keyPath: 'draft_id' });
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const tx = open.result.transaction('drafts', 'readwrite');
          tx.objectStore('drafts').put(draft);
          tx.oncomplete = () => {
            open.result.close();
            resolve();
          };
          tx.onerror = () => reject(tx.error);
        };
      }),
    [DRAFT_DB_NAME, SEEDED_DRAFT] as const,
  );
  await page.reload();
  await expect(
    page.getByRole('heading', { level: 2, name: 'Waiting to save (1)' }),
  ).toBeVisible();
  await expect(page.getByText('Needs attention')).toBeVisible();
  await expect(historyRows(page)).toHaveCount(1);
};

export const openAttemptForm = async (page: Page): Promise<void> => {
  await seedSession(page);
  await page.clock.setFixedTime(FIXED_NOW);
  await page.goto(ATTEMPT_FORM_PATH);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Log an attempt' }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 2, name: /synthetic/i }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Save attempt' }),
  ).toBeEnabled();
};

export const FUTURE_MESSAGE =
  'That time is in the future. Pick a time that has already happened.';

/** The attempt form after a Save with a client-rejected time (mirrors OCCURRED_AT_IN_FUTURE). */
export const openAttemptFormWithError = async (page: Page): Promise<void> => {
  await openAttemptForm(page);
  await page.getByLabel(/when/i).fill('2027-01-01T10:00');
  await page.getByRole('button', { name: 'Save attempt' }).click();
  await expect(page.getByText(FUTURE_MESSAGE)).toBeVisible();
  await expect(page.getByLabel(/when/i)).toHaveAttribute(
    'aria-invalid',
    'true',
  );
  // Frame the rejected field and its message for the viewport screenshot.
  await page
    .getByLabel(/when/i)
    .evaluate((el) => el.scrollIntoView({ block: 'start' }));
  // Park the pointer so the click position does not leave a hover highlight.
  await page.mouse.move(0, 0);
};

export const openNotFound = async (page: Page): Promise<void> => {
  await seedSession(page);
  await page.goto('/no-such-page');
  await expect(
    page.getByRole('heading', {
      level: 1,
      name: /isn't available or isn't yours/i,
    }),
  ).toBeVisible();
};

export interface PageState {
  /** Stable id: screenshot file name and test title. */
  readonly name: string;
  readonly open: (page: Page) => Promise<void>;
}

/** Every page in its meaningful state; the order follows the journey map. */
export const PAGE_STATES: readonly PageState[] = [
  { name: 'onboarding-account', open: (p) => walkOnboarding(p, 'account') },
  { name: 'onboarding-consent', open: (p) => walkOnboarding(p, 'consent') },
  { name: 'onboarding-board', open: (p) => walkOnboarding(p, 'board') },
  { name: 'onboarding-goals', open: (p) => walkOnboarding(p, 'goals') },
  { name: 'explore', open: openExplore },
  { name: 'route-detail', open: openRouteDetail },
  { name: 'session-builder', open: openSessionBuilder },
  { name: 'session-lineup', open: openSessionLineup },
  { name: 'decision', open: openDecision },
  { name: 'attempts-history-draft', open: openAttemptsHistoryWithDraft },
  { name: 'attempt-form-error', open: openAttemptFormWithError },
  { name: 'not-found', open: openNotFound },
];
