import { randomUUID } from 'node:crypto';
import { expect, test, type Request, type Route } from '@playwright/test';
import {
  ANGLE_DEG,
  BOARD_ID,
  apiClient,
  coldStartNotice,
  historyRows,
  lineupCards,
  readDecisionId,
  readLineup,
  readToken,
  rankedIds,
  registerAndOnboardViaUi,
  sessionIdFromUrl,
  stepHeading,
  uniqueUsername,
  type ApiResult,
  type Attempt,
  type AttemptCreate,
  type AttemptList,
  type ConsentDocument,
  type Decision,
  type ErrorEnvelope,
  type RecommendationResponse,
} from './fixtures';

/**
 * BC-0121: the M1/G1 vertical slice as ONE ordered journey against the real stack
 * (planning/00 section 3: make dev && make seed-demo -> onboard -> browse synthetic routes ->
 * recommend -> log twice (one canonical attempt) -> history; decision record exists). Each
 * step is a test.step so the trace shows where it stopped. UI assertions are role/label
 * based; the server-side truth is re-read through the API with the browser's own token.
 */

const EXPLORE_HEADING = `Routes at ${ANGLE_DEG} degrees`;
const NOTES = 'Two goes, sent the second.';
const ATTEMPTS_PATH = '/v1/me/attempts';

const isAttemptPost = (req: Request): boolean =>
  req.method() === 'POST' && new URL(req.url()).pathname === ATTEMPTS_PATH;
/** Same reference for page.route and page.unroute, which match by matcher identity. */
const isAttemptsUrl = (url: URL): boolean => url.pathname === ATTEMPTS_PATH;

/** What the server answered to the browser's forwarded POST and to its concurrent twin. */
interface ConcurrentSubmission {
  readonly key: string;
  readonly rawBody: string;
  readonly forwarded: { readonly status: number; readonly body: Attempt };
  readonly parallel: ApiResult<Attempt>;
}

/** A promise settled from inside a route handler, where a plain `let` would not narrow. */
const deferred = <T>() => {
  let settled = false;
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = (value) => {
      settled = true;
      res(value);
    };
    reject = (reason) => {
      settled = true;
      rej(reason);
    };
  });
  return { promise, resolve, reject, isSettled: () => settled };
};

test('onboard -> browse -> recommend -> log twice -> history -> cold start ends', async ({
  page,
  request,
}, testInfo) => {
  const username = uniqueUsername(testInfo.project.name);
  const anonymous = apiClient(request);
  const consentDoc = await anonymous.get<ConsentDocument>(
    '/v1/consent/current',
  );
  expect(consentDoc.status).toBe(200);

  await test.step('a. onboarding through the real UI lands on /explore with synthetic rows', async () => {
    await registerAndOnboardViaUi(page, username, consentDoc.body);
    await expect(
      page.getByRole('heading', { level: 2, name: EXPLORE_HEADING }),
    ).toBeVisible();
    await expect(
      page.getByText('Synthetic demo board', { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('status').filter({ hasText: /\d+ routes loaded/ }),
    ).toBeVisible();
    const rows = page
      .getByRole('region', { name: EXPLORE_HEADING })
      .getByRole('listitem');
    expect(await rows.count()).toBeGreaterThan(0);
  });

  const token = await readToken(page);
  const api = apiClient(request, token);

  await test.step('b. browse: Load more appends, route detail draws the board', async () => {
    const rows = page
      .getByRole('region', { name: EXPLORE_HEADING })
      .getByRole('listitem');
    const before = await rows.count();
    await expect(
      page.getByText(`${before} routes loaded, more available`),
    ).toBeVisible();
    await page.getByRole('button', { name: 'Load more routes' }).click();
    await expect
      .poll(() => rows.count(), { message: 'Load more appends rows' })
      .toBeGreaterThan(before);
    const after = await rows.count();
    await expect(page.getByText(`${after} routes loaded`)).toBeVisible();

    await rows.first().getByRole('link').click();
    await expect(page).toHaveURL(/\/explore\/[^/]+$/);
    // The loading branch renders an empty skeleton h1; the facts aside only exists once loaded.
    const facts = page.getByRole('complementary', { name: 'Route facts' });
    await expect(facts).toBeVisible();
    const title = page.getByRole('heading', { level: 1 });
    await expect(title).not.toBeEmpty();
    const routeName = (await title.textContent())?.trim() ?? '';
    expect(routeName).not.toBe('');
    await expect(
      page.getByRole('img', {
        name: new RegExp(`route ${routeName} uses \\d+`, 'i'),
      }),
    ).toBeVisible();
    await expect(page.getByRole('group', { name: 'Hold roles' })).toBeVisible();
    await expect(facts.getByText(/^\d+ placements?$/)).toBeVisible();
    await expect(
      page.getByRole('link', { name: 'Log an attempt on this route' }),
    ).toBeVisible();
  });

  let firstSessionId = '';
  let lineup: Awaited<ReturnType<typeof readLineup>> = [];
  let decisionId = '';
  let dismissedId = '';

  await test.step('c. recommend: lineup with reasons, cold-start notice, decision resolvable via API, dismiss re-requests', async () => {
    await page.getByRole('link', { name: 'Session' }).click();
    await expect(page).toHaveURL(/\/session\?session_id=[0-9a-f-]{36}/);
    firstSessionId = sessionIdFromUrl(page);
    await expect(
      page.getByRole('heading', { level: 1, name: 'Build a session' }),
    ).toBeVisible();
    await expect(page.getByRole('radio', { name: /technique/i })).toBeChecked();
    await expect(page.getByLabel('Routes in the session')).toHaveValue('4');
    await expect(page.getByLabel('Current board and angle')).toContainText(
      BOARD_ID,
    );

    await page.getByRole('button', { name: 'Build my session' }).click();
    await expect(stepHeading(page, /your session: 4 routes/i)).toBeVisible();
    await expect(coldStartNotice(page)).toBeVisible();
    await expect(coldStartNotice(page)).toContainText(
      /recommendations are heuristic until you log/i,
    );
    await expect(lineupCards(page)).toHaveCount(4);
    await expect(
      page.getByText('Matches your board and angle').first(),
    ).toBeVisible();
    await expect(page.getByText('New to you').first()).toBeVisible();
    await expect(page.getByText('No personalised estimate yet')).toHaveCount(4);
    await expect(
      page.getByRole('link', { name: 'Why this route' }),
    ).toHaveCount(4);

    lineup = await readLineup(page);
    expect(lineup.map((c) => c.routeVariantId)).toHaveLength(4);
    expect(new Set(lineup.map((c) => c.routeVariantId)).size).toBe(4);
    decisionId = await readDecisionId(page);
    for (const href of await page
      .getByRole('link', { name: 'Why this route' })
      .evaluateAll((links) => links.map((a) => a.getAttribute('href')))) {
      expect(href).toContain(`/decisions/${decisionId}`);
    }

    const decision = await api.get<Decision>(`/v1/me/decisions/${decisionId}`);
    expect(decision.status).toBe(200);
    expect(decision.body.decision_id).toBe(decisionId);
    expect(decision.body.cold_start).toBe(true);
    expect(decision.body.request.session_id).toBe(firstSessionId);
    expect(rankedIds(decision.body.recommendations)).toEqual(
      lineup.map((c) => c.routeVariantId),
    );
    for (const item of decision.body.recommendations) {
      expect(item.reason_codes).toContain('COLD_START_HEURISTIC');
    }

    const second = lineupCards(page).nth(1);
    dismissedId = lineup[1].routeVariantId;
    await second.getByRole('button', { name: 'Dismiss' }).click();
    await expect(stepHeading(page, /your session: 3 routes/i)).toBeVisible();
    await expect(page.getByText(/dismissed: 1 route/i)).toBeVisible();
    const afterDismiss = await readLineup(page);
    expect(afterDismiss).toHaveLength(3);
    expect(afterDismiss.map((c) => c.routeVariantId)).not.toContain(
      dismissedId,
    );
    expect(sessionIdFromUrl(page)).toBe(firstSessionId);
    const secondDecisionId = await readDecisionId(page);
    expect(secondDecisionId).not.toBe(decisionId);
    const redo = await api.get<Decision>(
      `/v1/me/decisions/${secondDecisionId}`,
    );
    expect(redo.status).toBe(200);
    expect(rankedIds(redo.body.recommendations)).toEqual(
      afterDismiss.map((c) => c.routeVariantId),
    );
    lineup = afterDismiss;
    decisionId = secondDecisionId;
  });

  const [first, secondRoute, third] = lineup;
  const attemptsFor = async (routeVariantId: string): Promise<Attempt[]> => {
    const res = await api.get<AttemptList>('/v1/me/attempts?limit=100');
    expect(res.status).toBe(200);
    return res.body.items.filter((a) => a.route_variant_id === routeVariantId);
  };

  await test.step('d1. log twice (double Save) -> one browser POST; that key submitted twice concurrently and replayed -> exactly one attempt, revision 1', async () => {
    await lineupCards(page)
      .first()
      .getByRole('link', { name: 'Log attempt' })
      .click();
    await expect(page).toHaveURL(
      new RegExp(
        `/attempts/new\\?route_variant_id=${encodeURIComponent(first.routeVariantId).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`,
      ),
    );
    await expect(
      page.getByRole('heading', { level: 1, name: 'Log an attempt' }),
    ).toBeVisible();
    await expect(
      page.getByRole('heading', { level: 2, name: first.name }),
    ).toBeVisible();
    await page.getByRole('button', { name: 'One attempt more' }).click();
    await page.getByRole('switch', { name: /sent/i }).check();
    await page.getByLabel(/notes/i).fill(NOTES);
    const save = page.getByRole('button', { name: 'Save attempt' });
    await expect(save).toBeEnabled();

    // BC-0124 HIGH-1: the form's in-flight guard turns a double click into ONE request, so the
    // UI alone cannot show that the server collapses a duplicate submission. Every POST the
    // browser sends is recorded (to document the guard), and the one it does send is
    // intercepted: it is forwarded with route.fetch() while the test re-sends the identical
    // Idempotency-Key and raw body through the API client in parallel, so the server sees two
    // concurrent submissions of one key. The browser is then answered with its own response.
    const browserPosts: Request[] = [];
    const recordPost = (req: Request): void => {
      if (isAttemptPost(req)) browserPosts.push(req);
    };
    page.on('request', recordPost);
    const duplicate = deferred<ConcurrentSubmission>();
    const interceptOnce = async (route: Route): Promise<void> => {
      if (!isAttemptPost(route.request()) || duplicate.isSettled()) {
        await route.fallback();
        return;
      }
      try {
        const key = route.request().headers()['idempotency-key'] ?? '';
        const rawBody = route.request().postData() ?? '';
        const [forwarded, parallel] = await Promise.all([
          route.fetch(),
          api.post<Attempt>('/v1/me/attempts', rawBody, {
            'Idempotency-Key': key,
            'Content-Type': 'application/json',
          }),
        ]);
        const forwardedBody = (await forwarded.json()) as Attempt;
        await route.fulfill({ response: forwarded });
        duplicate.resolve({
          key,
          rawBody,
          forwarded: { status: forwarded.status(), body: forwardedBody },
          parallel,
        });
      } catch (error) {
        duplicate.reject(error);
        await route.abort();
      }
    };
    await page.route(isAttemptsUrl, interceptOnce);
    const browserResponse = page.waitForResponse((res) =>
      isAttemptPost(res.request()),
    );
    await save.dblclick();
    const [response, submission] = await Promise.all([
      browserResponse,
      duplicate.promise,
    ]);
    await page.unroute(isAttemptsUrl, interceptOnce);

    await expect(page).toHaveURL(/\/attempts$/);
    await expect(page.getByText('Saved as revision 1.')).toBeVisible();
    await expect(page.getByText('1 attempt loaded')).toBeVisible();
    await expect(historyRows(page)).toHaveCount(1);
    const row = historyRows(page).first();
    await expect(row.getByRole('heading', { level: 3 })).toHaveText(first.name);
    await expect(row.getByText(NOTES)).toBeVisible();
    await expect(row.getByText('Revision 1', { exact: true })).toBeVisible();
    await expect(row.getByText('2 attempts')).toBeVisible();
    page.off('request', recordPost);

    // The UI guard: two clicks, one request, carrying the draft's key and the form's body.
    expect(browserPosts).toHaveLength(1);
    expect(submission.key).toMatch(/^[A-Za-z0-9._:-]{1,128}$/);
    expect(browserPosts[0].headers()['idempotency-key']).toBe(submission.key);
    const sentBody = JSON.parse(submission.rawBody) as Record<string, unknown>;
    expect(sentBody).toMatchObject({
      route_variant_id: first.routeVariantId,
      attempts_count: 2,
      sent: true,
      notes: NOTES,
    });

    // The server: two concurrent submissions of one key are one 201 and one 200 (either
    // order) for the same attempt, and the browser was answered with its own forwarded reply.
    const attemptId = submission.forwarded.body.attempt_id;
    expect(
      [submission.forwarded.status, submission.parallel.status].sort(),
    ).toEqual([200, 201]);
    expect(submission.parallel.body.attempt_id).toBe(attemptId);
    expect(submission.parallel.body.revision).toBe(1);
    expect(submission.forwarded.body.revision).toBe(1);
    expect(response.status()).toBe(submission.forwarded.status);
    expect(((await response.json()) as Attempt).attempt_id).toBe(attemptId);

    // A later replay of the same key and body, as a retry would send it, is the 200 replay.
    const replayed = await api.post<Attempt>(
      '/v1/me/attempts',
      submission.rawBody,
      { 'Idempotency-Key': submission.key, 'Content-Type': 'application/json' },
    );
    expect(replayed.status).toBe(200);
    expect(replayed.body.attempt_id).toBe(attemptId);
    expect(replayed.body.revision).toBe(1);

    const mine = await attemptsFor(first.routeVariantId);
    expect(mine).toHaveLength(1);
    expect(mine[0]).toMatchObject({
      attempt_id: attemptId,
      revision: 1,
      status: 'ACTIVE',
      attempts_count: 2,
      sent: true,
    });
    const all = await api.get<AttemptList>('/v1/me/attempts');
    expect(all.body.items).toHaveLength(1);
  });

  await test.step('d2. correct -> revision 2; retract -> Retracted badge', async () => {
    const row = historyRows(page).first();
    await row.getByRole('button', { name: 'Correct' }).click();
    await row.getByRole('button', { name: 'One attempt more' }).click();
    await row.getByRole('button', { name: 'Save correction' }).click();
    await expect(row.getByText('Revision 2', { exact: true })).toBeVisible();
    await expect(row.getByText('Corrected', { exact: true })).toBeVisible();
    await expect(row.getByText('3 attempts')).toBeVisible();
    let [attempt] = await attemptsFor(first.routeVariantId);
    expect(attempt).toMatchObject({
      revision: 2,
      attempts_count: 3,
      status: 'ACTIVE',
    });

    await row.getByRole('button', { name: 'Undo (retract)' }).click();
    await row
      .getByRole('group', { name: 'Confirm retract' })
      .getByRole('button', { name: 'Retract' })
      .click();
    await expect(row.getByText('Retracted', { exact: true })).toBeVisible();
    await expect(row.getByText('Revision 3', { exact: true })).toBeVisible();
    await expect(row.getByRole('button', { name: 'Correct' })).toHaveCount(0);
    [attempt] = await attemptsFor(first.routeVariantId);
    expect(attempt).toMatchObject({ revision: 3, status: 'RETRACTED' });
    expect(await attemptsFor(first.routeVariantId)).toHaveLength(1);
  });

  await test.step('d3. a second real attempt on another route -> two rows', async () => {
    await page.goto(
      `/explore/${encodeURIComponent(secondRoute.routeVariantId)}`,
    );
    await expect(
      page.getByRole('heading', { level: 1, name: secondRoute.name }),
    ).toBeVisible();
    await page
      .getByRole('link', { name: 'Log an attempt on this route' })
      .click();
    await expect(
      page.getByRole('heading', { level: 2, name: secondRoute.name }),
    ).toBeVisible();
    await page.getByRole('switch', { name: /sent/i }).check();
    await page.getByRole('button', { name: 'Save attempt' }).click();

    await expect(page).toHaveURL(/\/attempts$/);
    await expect(page.getByText('Saved as revision 1.')).toBeVisible();
    await expect(page.getByText('2 attempts loaded')).toBeVisible();
    await expect(historyRows(page)).toHaveCount(2);
  });

  const expectHistoryNewestFirst = async (): Promise<void> => {
    const rows = historyRows(page);
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0).getByRole('heading', { level: 3 })).toHaveText(
      secondRoute.name,
    );
    await expect(
      rows.nth(0).getByText('Revision 1', { exact: true }),
    ).toBeVisible();
    await expect(rows.nth(1).getByRole('heading', { level: 3 })).toHaveText(
      first.name,
    );
    await expect(
      rows.nth(1).getByText('Retracted', { exact: true }),
    ).toBeVisible();
  };

  await test.step('e. history is newest first and survives a reload (server-side)', async () => {
    await expectHistoryNewestFirst();
    await page.reload();
    await expect(page.getByText('2 attempts loaded')).toBeVisible();
    await expectHistoryNewestFirst();
    const list = await api.get<AttemptList>('/v1/me/attempts');
    expect(list.status).toBe(200);
    expect(list.body.items.map((a) => a.route_variant_id)).toEqual([
      secondRoute.routeVariantId,
      first.routeVariantId,
    ]);
    expect(
      list.body.items[0].occurred_at >= list.body.items[1].occurred_at,
    ).toBe(true);
  });

  await test.step('f. cold start ends: a new session is no longer cold_start in the API or the UI', async () => {
    await page.getByRole('link', { name: 'Session' }).click();
    await expect(page).toHaveURL(/\/session\?session_id=[0-9a-f-]{36}/);
    const newSessionId = sessionIdFromUrl(page);
    expect(newSessionId).not.toBe(firstSessionId);
    await page.getByRole('button', { name: 'Build my session' }).click();
    await expect(stepHeading(page, /your session: \d+ routes/i)).toBeVisible();
    await expect(coldStartNotice(page)).toHaveCount(0);
    const warmDecision = await api.get<Decision>(
      `/v1/me/decisions/${await readDecisionId(page)}`,
    );
    expect(warmDecision.status).toBe(200);
    expect(warmDecision.body.cold_start).toBe(false);
    expect(warmDecision.body.request.session_id).toBe(newSessionId);

    const direct = await api.post<RecommendationResponse>(
      '/v1/me/sessions/recommendations',
      {
        board_id: BOARD_ID,
        angle_deg: ANGLE_DEG,
        goal: 'TECHNIQUE',
        max_routes: 4,
        session_id: randomUUID(),
        exclude_route_variant_ids: [],
      },
    );
    expect(direct.status).toBe(200);
    expect(direct.body.cold_start).toBe(false);
    expect(direct.body.recommendations).toHaveLength(4);
    // The sent route is inside the repeat-avoidance window, so it is not offered again.
    expect(
      direct.body.recommendations.map((r) => r.route_variant_id),
    ).not.toContain(secondRoute.routeVariantId);
    for (const item of direct.body.recommendations) {
      expect(item.reason_codes).not.toContain('COLD_START_HEURISTIC');
    }
    // BC-0310: the features behind this ranking include the attempt logged in d3 (the only
    // ACTIVE one: d1's was retracted), so feature_as_of is at or after its occurred_at, and the
    // source names the read path (SYNC until the stream path lands; either is contract-valid).
    const [logged] = await attemptsFor(secondRoute.routeVariantId);
    expect([
      Date.parse(direct.body.feature_as_of) >= Date.parse(logged.occurred_at),
      direct.body.features_source,
    ]).toEqual([true, expect.stringMatching(/^(STREAM|SYNC)$/)]);
  });

  await test.step('g. the same Idempotency-Key replays as 200 with the same attempt_id; a different body is 409', async () => {
    const key = randomUUID();
    const body: AttemptCreate = {
      route_variant_id: third.routeVariantId,
      occurred_at: new Date().toISOString(),
      attempts_count: 1,
      sent: false,
    };
    const created = await api.post<Attempt>('/v1/me/attempts', body, {
      'Idempotency-Key': key,
    });
    expect(created.status).toBe(201);
    const replayed = await api.post<Attempt>('/v1/me/attempts', body, {
      'Idempotency-Key': key,
    });
    expect(replayed.status).toBe(200);
    expect(replayed.body.attempt_id).toBe(created.body.attempt_id);
    expect(replayed.body.revision).toBe(1);
    expect(await attemptsFor(third.routeVariantId)).toHaveLength(1);

    const conflict = await api.post<ErrorEnvelope>(
      '/v1/me/attempts',
      { ...body, attempts_count: 2 },
      { 'Idempotency-Key': key },
    );
    expect(conflict.status).toBe(409);
    expect(conflict.body.code).toBe('IDEMPOTENCY_CONFLICT');
    expect(await attemptsFor(third.routeVariantId)).toHaveLength(1);
  });

  await test.step('h. the decision record page opens for the dismissed lineup', async () => {
    await page.goto(`/decisions/${decisionId}`);
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
    await expect(about).toBeVisible();
    // BC-0210: heuristic-v2 is the server default (contract 1.3.0).
    await expect(about.getByText('heuristic-v2')).toBeVisible();
    await expect(
      page
        .getByText('This route exists on your board at your selected angle.')
        .first(),
    ).toBeVisible();
  });
});
