import { expect, test, type Page } from '@playwright/test';

/**
 * The demo journey on the Expo web build with mocks: register, consent, board and angle,
 * goals, build a session (heuristic-v2 by default), log an attempt (the lineup refreshes),
 * then open the route and follow "More like this".
 */
const byId = (page: Page, id: string) => page.getByTestId(id).first();

test('register, onboard, get recommendations, log an attempt, more like this', async ({
  page,
}) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));

  await page.goto('/');
  await expect(
    page.getByRole('heading', { name: 'Choose a username' }),
  ).toBeVisible({
    timeout: 30_000,
  });

  // 1. Account
  await byId(page, 'username').fill('smoke_climber');
  await byId(page, 'password').fill('long-enough-pw');
  await byId(page, 'account-submit').click();

  // 2. Consent: the button stays disabled until the service box is ticked.
  await expect(
    page.getByRole('heading', { name: 'Decide what Beacon may keep' }),
  ).toBeVisible();
  await expect(byId(page, 'consent-submit')).toHaveAttribute(
    'aria-disabled',
    'true',
  );
  await byId(page, 'consent-service').click();
  await byId(page, 'consent-submit').click();

  // 3. Board and angle
  await expect(
    page.getByRole('heading', { name: 'Which board do you climb on?' }),
  ).toBeVisible();
  await byId(page, 'angle-40').click();
  await byId(page, 'board-continue').click();

  // 4. Level and goals
  await byId(page, 'band-INTERMEDIATE').click();
  await byId(page, 'goal-TECHNIQUE').click();
  await byId(page, 'goals-submit').click();

  // Session: recommendations with the board, grade and the why.
  await expect(
    page.getByRole('heading', { name: 'Build a session' }),
  ).toBeVisible();
  await byId(page, 'build-session').click();
  const hero = byId(page, 'lineup-hero');
  await expect(hero).toBeVisible();
  await expect(hero.getByText('Why this route')).toBeVisible();
  await expect(byId(page, 'hero-board')).toHaveAttribute(
    'aria-label',
    /^Board with \d+ holds?\./,
  );
  await expect(page.getByText(/Policy heuristic-v2/)).toBeVisible();
  const firstPick = await byId(page, 'hero-name').innerText();

  // Log an attempt on the first pick; back on the session the lineup is refreshed.
  await byId(page, 'hero-log').click();
  await expect(byId(page, 'log-attempt')).toBeVisible();
  await byId(page, 'sent-yes').click();
  await byId(page, 'effort-6').click();
  await byId(page, 'save-attempt').click();
  await expect(byId(page, 'session-status')).toHaveText(
    new RegExp(`^Logged ${firstPick}\\. \\d+ picks refreshed\\.$`),
  );

  // The attempt is in the log.
  await byId(page, 'tab-attempts').click();
  await expect(
    page.getByRole('heading', { name: 'Attempt log' }),
  ).toBeVisible();
  await expect(page.getByText(firstPick).first()).toBeVisible();

  // Route detail and "More like this".
  await byId(page, 'tab-session').click();
  await byId(page, 'hero-open').click();
  await expect(byId(page, 'route-detail')).toBeVisible();
  const similar = byId(page, 'more-like-this');
  await similar.scrollIntoViewIfNeeded();
  await expect(similar.getByText('More like this')).toBeVisible();
  const neighbour = similar.locator('[data-testid^="similar-"]').first();
  await expect(neighbour).toBeVisible();
  const neighbourName = (await neighbour.getAttribute('aria-label'))!.split(
    ',',
  )[0]!;
  await neighbour.click();
  await expect(
    page.getByRole('heading', { name: neighbourName }),
  ).toBeVisible();

  expect(errors).toEqual([]);
});

test('every control in the tab bar is labelled and at least 44 pt tall', async ({
  page,
}) => {
  await page.goto('/');
  await byId(page, 'username').fill('tab_checker');
  await byId(page, 'password').fill('long-enough-pw');
  await byId(page, 'account-submit').click();
  await byId(page, 'consent-service').click();
  await byId(page, 'consent-submit').click();
  await byId(page, 'angle-40').click();
  await byId(page, 'board-continue').click();
  await byId(page, 'band-NOVICE').click();
  await byId(page, 'goals-submit').click();

  const tabs = page.getByRole('tab');
  await expect(tabs).toHaveCount(4);
  for (const name of ['Session', 'Explore', 'Log', 'Scan']) {
    const tab = page.getByRole('tab', { name });
    const box = await tab.boundingBox();
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
  }
  await page.getByRole('tab', { name: 'Scan' }).click();
  await expect(
    page.getByRole('heading', { name: 'Scan a board' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Scan (not available yet)' }),
  ).toHaveAttribute('aria-disabled', 'true');
});
