import { expect, test, type Page } from '@playwright/test';
import { openAttemptForm, openExplore, openSessionLineup } from './states';

/**
 * BC-0119 reduced motion (project `reduced-motion`: 1024 wide, `reducedMotion: 'reduce'`).
 * tokens.css answers `prefers-reduced-motion: reduce` by collapsing every duration token
 * to 0.01ms (not 0, so `transitionend` still fires and state machines that wait for it
 * do not hang) and stops the skeleton pulse. The three pages with the most motion are
 * walked: no element may keep a transition or animation longer than that.
 */

/** 0.01ms expressed in seconds, the value the tokens collapse to. */
const MAX_DURATION_S = 0.00001;

const parseSeconds = (value: string): number =>
  value.trim().endsWith('ms')
    ? parseFloat(value) / 1000
    : parseFloat(value) || 0;

interface MotionEntry {
  readonly selector: string;
  readonly property: 'animation' | 'transition';
  /** Computed `animation-duration` / `transition-duration` (comma list). */
  readonly duration: string;
}

const findMotion = (page: Page): Promise<readonly MotionEntry[]> =>
  page.evaluate(() => {
    const describe = (el: Element): string =>
      `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${
        el.classList.length ? `.${[...el.classList].join('.')}` : ''
      }`;
    const entries: MotionEntry[] = [];
    for (const el of document.querySelectorAll('*')) {
      const style = getComputedStyle(el);
      if (style.animationName !== 'none')
        entries.push({
          selector: `${describe(el)} (${style.animationName})`,
          property: 'animation',
          duration: style.animationDuration,
        });
      // Every element reports a transition duration; only non-zero ones matter.
      entries.push({
        selector: describe(el),
        property: 'transition',
        duration: style.transitionDuration,
      });
    }
    return entries;
  });

const PAGES = [
  { name: 'explore', open: openExplore },
  { name: 'session-lineup', open: openSessionLineup },
  { name: 'attempt-form', open: openAttemptForm },
] as const;

for (const target of PAGES) {
  test(`no running motion on ${target.name}`, async ({ page }) => {
    await target.open(page);
    await expect
      .poll(() =>
        page.evaluate(
          () => matchMedia('(prefers-reduced-motion: reduce)').matches,
        ),
      )
      .toBe(true);

    const motion = await findMotion(page);
    const tooLong = motion.filter((m) =>
      m.duration.split(',').some((part) => parseSeconds(part) > MAX_DURATION_S),
    );
    test.info().annotations.push({
      type: 'motion-summary',
      description: `${motion.length} elements inspected, ${tooLong.length} with motion over ${MAX_DURATION_S}s`,
    });
    expect(
      tooLong.map((m) => `${m.selector} ${m.property}: ${m.duration}`),
      'elements that still animate under prefers-reduced-motion',
    ).toEqual([]);
  });
}
