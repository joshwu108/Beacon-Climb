import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { PAGE_STATES } from './states';

/**
 * BC-0119 accessibility gate: axe-core on every page in its meaningful state, on every
 * breakpoint project (bp-320 .. bp-1920). Serious and critical violations fail the test;
 * moderate and minor ones are attached as annotations (visible in the HTML report under
 * the test's "Annotations") so they are tracked without blocking.
 */

const BLOCKING = new Set(['serious', 'critical']);

for (const state of PAGE_STATES) {
  test(`axe: ${state.name}`, async ({ page }) => {
    await state.open(page);

    const results = await new AxeBuilder({ page }).analyze();
    const describe = (v: (typeof results.violations)[number]): string =>
      `${v.id} (${v.impact}): ${v.help} [${v.nodes
        .map((n) => n.target.join(' '))
        .join(', ')}]`;

    for (const v of results.violations) {
      if (BLOCKING.has(v.impact ?? '')) continue;
      test.info().annotations.push({
        type: `axe-${v.impact ?? 'unknown'}`,
        description: describe(v),
      });
    }
    test.info().annotations.push({
      type: 'axe-summary',
      description: `${results.passes.length} rules passed, ${results.violations.length} violations, ${results.incomplete.length} incomplete`,
    });

    const blocking = results.violations
      .filter((v) => BLOCKING.has(v.impact ?? ''))
      .map(describe);
    expect(blocking, 'serious/critical axe violations').toEqual([]);
  });
}
