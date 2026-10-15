import { expect, test } from '@playwright/test';
import { PAGE_STATES } from './states';

/**
 * BC-0119 responsive gate on every breakpoint project (bp-320 .. bp-1920): the document
 * never scrolls sideways and the main landmark is visible. At 320 / 768 / 1024 / 1440 the
 * viewport is also compared with the committed baseline
 * (e2e/__screenshots__/<project>/<state>.png). This folds in the BC-0116 explore
 * screenshots (explore-screenshots.spec.ts is gone; `explore` and `route-detail` keep
 * their file names under the new project directories).
 *
 * Baselines are generated on macOS; the comparison only runs on darwin until Linux
 * baselines are committed (see README), and the skip is recorded as an annotation so the
 * report shows it.
 */

const SCREENSHOT_WIDTHS = new Set([320, 768, 1024, 1440]);

for (const state of PAGE_STATES) {
  test(`layout: ${state.name}`, async ({ page }, testInfo) => {
    await state.open(page);

    const metrics = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
    }));
    expect(
      metrics.scrollWidth,
      `horizontal overflow: scrollWidth ${metrics.scrollWidth} > innerWidth ${metrics.innerWidth}`,
    ).toBeLessThanOrEqual(metrics.innerWidth);
    await expect(page.getByRole('main')).toBeVisible();

    const width = testInfo.project.use.viewport?.width ?? 0;
    if (!SCREENSHOT_WIDTHS.has(width)) return;
    if (process.platform !== 'darwin') {
      testInfo.annotations.push({
        type: 'screenshot-skipped',
        description: `baselines are macOS-only; platform is ${process.platform}`,
      });
      return;
    }
    await expect(page).toHaveScreenshot(`${state.name}.png`, {
      fullPage: false,
    });
  });
}
