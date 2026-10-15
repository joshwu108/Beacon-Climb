import { expect, test, type Page } from '@playwright/test';
import {
  ATTEMPT_FORM_PATH,
  FIXED_NOW,
  lineupHeading,
  openAttemptForm,
  openDecision,
  openExplore,
  openNotFound,
  openRouteDetail,
  openSessionBuilder,
  openSessionLineup,
  PAGE_STATES,
  seedSession,
  walkOnboarding,
} from './states';

/**
 * BC-0119 keyboard paths (project `keyboard`, 1024 wide): the skip link is the first Tab
 * stop and lands focus on main; the account step, the explore list, the session lineup
 * and the attempt form all work with Tab / Enter / Space alone; and every one of the
 * first 15 Tab stops on each page shows a focus indicator.
 */

const MAX_TAB_STOPS = 15;
const MAX_TABS_TO_REACH = 40;

interface FocusSnapshot {
  readonly tag: string;
  readonly text: string;
  readonly isBody: boolean;
  readonly hasIndicator: boolean;
  readonly indicator: string;
}

/**
 * The focused element's computed focus ring. Controls drawn with an invisible native
 * input (the segmented angle picker: `opacity: 0`) carry their ring on the parent that
 * matches `:has(input:focus-visible)`, so that is what is inspected for them.
 */
const snapshotFocus = (page: Page): Promise<FocusSnapshot> =>
  page.evaluate(() => {
    const active = document.activeElement;
    if (!active || active === document.body)
      return {
        tag: 'body',
        text: '',
        isBody: true,
        hasIndicator: false,
        indicator: '',
      };
    const ownStyle = getComputedStyle(active);
    const target =
      ownStyle.opacity === '0' && active.parentElement
        ? active.parentElement
        : active;
    const style = getComputedStyle(target);
    const hasOutline =
      style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0;
    const hasShadow = style.boxShadow !== 'none';
    return {
      tag: `${active.tagName.toLowerCase()}${active.id ? `#${active.id}` : ''}`,
      text: (active.textContent ?? '').trim().slice(0, 40),
      isBody: false,
      hasIndicator: hasOutline || hasShadow,
      indicator: `outline ${style.outlineWidth} ${style.outlineStyle}; box-shadow ${style.boxShadow}`,
    };
  });

/** Press Tab until `locator` is focused (bounded), so tests do not depend on the exact tab order. */
const tabTo = async (
  page: Page,
  locator: ReturnType<Page['getByRole']>,
): Promise<void> => {
  for (let i = 0; i < MAX_TABS_TO_REACH; i += 1) {
    await page.keyboard.press('Tab');
    if (await locator.evaluate((el) => el === document.activeElement)) return;
  }
  throw new Error(
    `Tab did not reach ${String(locator)} in ${MAX_TABS_TO_REACH} stops`,
  );
};

/** Keyboard-only: Tab from the document, or Shift+Tab back from whatever has focus. */
const focusSkipLink = async (page: Page): Promise<void> => {
  const skip = page.getByRole('link', { name: 'Skip to main content' });
  for (let i = 0; i < MAX_TABS_TO_REACH; i += 1) {
    if (await skip.evaluate((el) => el === document.activeElement)) return;
    const onBody = await page.evaluate(
      () =>
        document.activeElement === null ||
        document.activeElement === document.body,
    );
    await page.keyboard.press(onBody ? 'Tab' : 'Shift+Tab');
  }
  throw new Error(
    `Could not reach the skip link in ${MAX_TABS_TO_REACH} presses`,
  );
};

test('the skip link is the first Tab stop and moves focus to main', async ({
  page,
}) => {
  await openExplore(page);
  await page.keyboard.press('Tab');
  const skip = page.getByRole('link', { name: 'Skip to main content' });
  await expect(skip).toBeFocused();
  await expect(skip).toBeInViewport();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('main')).toBeFocused();
});

test('the account step is completed and submitted with the keyboard only', async ({
  page,
}) => {
  await walkOnboarding(page, 'account');
  await tabTo(page, page.getByRole('textbox', { name: 'Username' }));
  await page.keyboard.type(`kb_${Date.now().toString(36)}`);
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Password', { exact: true })).toBeFocused();
  await page.keyboard.type('a-long-password');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/onboarding\/consent$/);
  await expect(
    page.getByRole('heading', {
      level: 2,
      name: /decide what beacon may keep/i,
    }),
  ).toBeFocused();
});

test('explore rows are Tab stops and Enter opens the route detail', async ({
  page,
}) => {
  await openExplore(page);
  const firstRow = page
    .getByRole('link', { name: /synthetic traverse 1/i })
    .first();
  await tabTo(page, firstRow);
  const href = await firstRow.getAttribute('href');
  expect(href).toMatch(/^\/explore\/synthetic-route-\d+%3A35$/);
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`${href}$`));
  await expect(
    page.getByRole('heading', { level: 1, name: /synthetic traverse \d+/i }),
  ).toBeVisible();
});

test('the session is built and a route swapped or dismissed by keyboard', async ({
  page,
}) => {
  await openSessionBuilder(page);
  await tabTo(page, page.getByRole('button', { name: 'Build my session' }));
  await page.keyboard.press('Enter');
  await expect(lineupHeading(page)).toBeVisible();
  // The lineup heading takes focus after a build, so the card buttons come next.
  await expect(lineupHeading(page)).toBeFocused();
  const swap = page.getByRole('button', { name: 'Swap' }).first();
  const dismiss = page.getByRole('button', { name: 'Dismiss' }).first();
  await tabTo(page, swap);
  await expect(swap).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(dismiss).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(
    page.getByRole('heading', { level: 2, name: /your session: 3 routes/i }),
  ).toBeVisible();
});

test('the attempt form is submitted with the keyboard', async ({ page }) => {
  await seedSession(page);
  await page.clock.setFixedTime(FIXED_NOW);
  await page.goto(ATTEMPT_FORM_PATH);
  const save = page.getByRole('button', { name: 'Save attempt' });
  await expect(save).toBeEnabled();
  await tabTo(page, page.getByRole('switch', { name: /sent/i }));
  await page.keyboard.press('Space');
  await expect(page.getByRole('switch', { name: /sent/i })).toBeChecked();
  await tabTo(page, save);
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/attempts$/);
  await expect(page.getByText('Saved as revision 1.')).toBeVisible();
});

const FOCUS_RING_STATES = [
  {
    name: 'onboarding-account',
    open: (p: Page) => walkOnboarding(p, 'account'),
  },
  { name: 'onboarding-board', open: (p: Page) => walkOnboarding(p, 'board') },
  { name: 'explore', open: openExplore },
  { name: 'route-detail', open: openRouteDetail },
  { name: 'session-lineup', open: openSessionLineup },
  { name: 'decision', open: openDecision },
  { name: 'attempt-form', open: openAttemptForm },
  { name: 'not-found', open: openNotFound },
] satisfies readonly (typeof PAGE_STATES)[number][];

for (const state of FOCUS_RING_STATES) {
  test(`every Tab stop shows a focus indicator: ${state.name}`, async ({
    page,
  }) => {
    await state.open(page);
    // Pages move focus on arrival (step heading, lineup heading), and Chromium keeps
    // the sequential-focus starting point there even after a blur. Walk back to the
    // skip link with the keyboard so stop 1 is the same on every page.
    await focusSkipLink(page);
    const missing: string[] = [];
    let stops = 0;
    for (let i = 0; i < MAX_TAB_STOPS; i += 1) {
      if (i > 0) await page.keyboard.press('Tab');
      const focus = await snapshotFocus(page);
      if (focus.isBody) break;
      stops += 1;
      if (!focus.hasIndicator)
        missing.push(`${focus.tag} "${focus.text}" (${focus.indicator})`);
    }
    expect(
      stops,
      'at least the skip link and the nav are Tab stops',
    ).toBeGreaterThan(4);
    expect(missing, 'Tab stops without a visible focus indicator').toEqual([]);
  });
}
