import {
  expect,
  type APIRequestContext,
  type APIResponse,
  type Locator,
  type Page,
} from '@playwright/test';

/**
 * BC-0121 helpers for the real-stack slice. Everything goes through nginx on :3000, the same
 * origin the browser uses, so the API assertions see exactly what the UI saw. No fixture
 * data: users are registered per run, the board is the seeded synthetic one.
 */

export const BASE_URL = process.env.E2E_BASE_URL ?? 'http://127.0.0.1:3000';
/** apps/web/src/features/auth/token-store.ts: {token, expiresAt} in sessionStorage. */
export const SESSION_STORAGE_KEY = 'beacon.session.v1';
export const PASSWORD = 'e2e-long-password';
export const DEMO = { username: 'demo', password: 'demo-password' } as const;
export const BOARD_ID = 'synthetic-demo-1';
export const ANGLE_DEG = 40;

const UUID_RE = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/;

/**
 * `e2e_<timestamp36>_<tag><random>`: lowercase [a-z0-9_] as AuthRequest.username requires,
 * unique per run, per project (tag) and per spec, so re-running on the same database passes.
 */
export const uniqueUsername = (tag: string): string => {
  const stamp = Date.now().toString(36);
  const random = Math.random().toString(36).slice(2, 7);
  const safeTag = tag
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '')
    .slice(0, 6);
  return `e2e_${stamp}_${safeTag}${random}`.slice(0, 32);
};

// --- API types (the subset of contracts/openapi.yaml 1.1.0 asserted here) -----------------

export interface ConsentDocument {
  readonly version: string;
  readonly text_sha256: string;
}

export interface Attempt {
  readonly attempt_id: string;
  readonly route_variant_id: string;
  readonly occurred_at: string;
  readonly attempts_count: number;
  readonly sent: boolean;
  readonly revision: number;
  readonly status: 'ACTIVE' | 'RETRACTED';
}

export interface AttemptList {
  readonly items: readonly Attempt[];
  readonly next_cursor: string | null;
}

export interface AttemptCreate {
  readonly route_variant_id: string;
  readonly occurred_at: string;
  readonly attempts_count: number;
  readonly sent: boolean;
}

export interface Recommendation {
  readonly route_variant_id: string;
  readonly position: number;
  readonly reason_codes: readonly string[];
}

export interface RecommendationResponse {
  readonly decision_id: string;
  readonly feature_as_of: string;
  readonly features_source: 'STREAM' | 'SYNC';
  readonly features_cutoff_w?: string;
  readonly cold_start: boolean;
  readonly fallback_used: boolean;
  readonly recommendations: readonly Recommendation[];
}

export interface Decision extends RecommendationResponse {
  readonly created_at: string;
  readonly request: { readonly session_id: string };
}

export interface ErrorEnvelope {
  readonly code: string;
  readonly message: string;
  readonly request_id: string;
}

export interface Me {
  readonly username: string;
  readonly preferences: {
    readonly board_id: string;
    readonly angle_deg: number;
  } | null;
}

export interface ApiResult<T> {
  readonly status: number;
  readonly body: T;
  readonly headers: Record<string, string>;
}

// --- API client ------------------------------------------------------------------------------

const parse = async <T>(response: APIResponse): Promise<ApiResult<T>> => {
  const text = await response.text();
  return {
    status: response.status(),
    body: (text === '' ? null : JSON.parse(text)) as T,
    headers: response.headers(),
  };
};

/** Thin client over Playwright's request context; `token` is the browser's bearer token. */
export const apiClient = (request: APIRequestContext, token?: string) => {
  const headers = (extra: Record<string, string> = {}) => ({
    Accept: 'application/json',
    ...(token === undefined ? {} : { Authorization: `Bearer ${token}` }),
    ...extra,
  });
  return {
    get: <T>(path: string): Promise<ApiResult<T>> =>
      request.get(`${BASE_URL}${path}`, { headers: headers() }).then(parse<T>),
    post: <T>(
      path: string,
      data: unknown,
      extra: Record<string, string> = {},
    ): Promise<ApiResult<T>> =>
      request
        .post(`${BASE_URL}${path}`, { headers: headers(extra), data })
        .then(parse<T>),
  };
};

export type ApiClient = ReturnType<typeof apiClient>;

/** The bearer token the SPA stored after register/sign-in. */
export const readToken = async (page: Page): Promise<string> => {
  const raw = await page.evaluate(
    (key) => sessionStorage.getItem(key),
    SESSION_STORAGE_KEY,
  );
  expect(raw, 'session token in sessionStorage').not.toBeNull();
  const parsed = JSON.parse(raw as string) as { token?: unknown };
  expect(typeof parsed.token).toBe('string');
  return parsed.token as string;
};

/** Sorted ascending by position, as the lineup renders them. */
export const rankedIds = (
  recommendations: readonly Recommendation[],
): string[] =>
  [...recommendations]
    .sort((a, b) => a.position - b.position)
    .map((r) => r.route_variant_id);

// --- UI helpers ------------------------------------------------------------------------------

export const stepHeading = (page: Page, name: RegExp): Locator =>
  page.getByRole('heading', { level: 2, name });

export const historyRows = (page: Page): Locator =>
  page.getByRole('region', { name: 'History' }).getByRole('listitem');

export const lineupCards = (page: Page): Locator =>
  page
    .getByRole('list', { name: 'Recommended routes in order' })
    .locator(':scope > li');

export const coldStartNotice = (page: Page): Locator =>
  page.getByRole('status').filter({ hasText: 'Starting picks' });

export interface LineupCard {
  readonly routeVariantId: string;
  readonly name: string;
}

const routeIdFromHref = (href: string | null): string => {
  const id = new URL(href ?? '', BASE_URL).searchParams.get('route_variant_id');
  if (id === null) throw new Error(`no route_variant_id in href ${href}`);
  return id;
};

/** Route id (from the card's "Log attempt" link) and name of every card, in order. */
export const readLineup = async (page: Page): Promise<LineupCard[]> => {
  const cards = lineupCards(page);
  const count = await cards.count();
  const out: LineupCard[] = [];
  for (let i = 0; i < count; i += 1) {
    const card = cards.nth(i);
    const href = await card
      .getByRole('link', { name: 'Log attempt' })
      .getAttribute('href');
    const name = await card
      .getByRole('heading', { level: 3 })
      .getByRole('link')
      .textContent();
    out.push({
      routeVariantId: routeIdFromHref(href),
      name: (name ?? '').trim(),
    });
  }
  return out;
};

/** The decision id behind the lineup's "Why these routes" link. */
export const readDecisionId = async (page: Page): Promise<string> => {
  const href = await page
    .getByRole('link', { name: 'Why these routes' })
    .getAttribute('href');
  const match = href?.match(UUID_RE);
  if (!match) throw new Error(`no decision id in href ${href}`);
  return match[0];
};

export const sessionIdFromUrl = (page: Page): string => {
  const id = new URL(page.url()).searchParams.get('session_id');
  if (id === null) throw new Error(`no session_id in ${page.url()}`);
  return id;
};

/**
 * First-run flow through the real UI: account, consent (checkbox; the version shown must be
 * the live document's), board + angle, band + goal, landing on /explore.
 */
export const registerAndOnboardViaUi = async (
  page: Page,
  username: string,
  consent: ConsentDocument,
): Promise<void> => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/onboarding\/account$/);
  await expect(stepHeading(page, /choose a username/i)).toBeVisible();
  await page.getByRole('textbox', { name: 'Username' }).fill(username);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();

  await expect(page).toHaveURL(/\/onboarding\/consent$/);
  await expect(stepHeading(page, /decide what beacon may keep/i)).toBeVisible();
  await expect(
    page.getByText(`Consent ${consent.version}`, { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(consent.text_sha256.slice(0, 12), { exact: false }),
  ).toBeVisible();
  const service = page.getByRole('checkbox', {
    name: /i agree to the service use/i,
  });
  const record = page.getByRole('button', { name: /record my choice/i });
  await expect(record).toBeDisabled();
  await service.check();
  await expect(
    page.getByRole('checkbox', { name: /research use/i }),
  ).not.toBeChecked();
  await record.click();

  await expect(page).toHaveURL(/\/onboarding\/board$/);
  await expect(stepHeading(page, /which board do you climb on/i)).toBeVisible();
  await page.getByRole('radio', { name: /synthetic/i }).check();
  await page.getByRole('radio', { name: `${ANGLE_DEG}°` }).check();
  await page.getByRole('button', { name: 'Continue' }).click();

  await expect(page).toHaveURL(/\/onboarding\/goals$/);
  await expect(stepHeading(page, /where to start/i)).toBeVisible();
  await page.getByRole('radio', { name: /intermediate/i }).check();
  await page.getByRole('checkbox', { name: /technique/i }).check();
  await page.getByRole('button', { name: /save and start exploring/i }).click();

  await expect(page).toHaveURL(/\/explore$/);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Explore routes' }),
  ).toBeVisible();
};

/** Sign in as the seeded demo user (make seed-demo) and land on /explore. */
export const loginAsDemo = async (page: Page): Promise<void> => {
  await page.goto('/onboarding');
  await page.getByRole('button', { name: 'Sign in instead' }).click();
  await page.getByRole('textbox', { name: 'Username' }).fill(DEMO.username);
  await page.getByLabel('Password', { exact: true }).fill(DEMO.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/explore$/);
};
