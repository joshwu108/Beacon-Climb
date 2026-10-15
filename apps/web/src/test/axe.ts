import axe, { type AxeResults, type Result } from 'axe-core';
import { expect } from 'vitest';

const BLOCKING_IMPACTS = new Set(['serious', 'critical']);

/** Run axe-core on a DOM node. color-contrast needs a real layout engine, so it is skipped in jsdom. */
export const runAxe = (node: Element): Promise<AxeResults> =>
  axe.run(node, { rules: { 'color-contrast': { enabled: false } } });

const describe = (violation: Result): string =>
  `${violation.id} (${violation.impact}): ${violation.help}\n  ${violation.nodes
    .map((n) => n.target.join(' '))
    .join('\n  ')}`;

/** Fail the test on serious or critical violations; report everything else as the message. */
export const expectNoBlockingViolations = async (
  node: Element,
): Promise<void> => {
  const results = await runAxe(node);
  const blocking = results.violations.filter((v) =>
    BLOCKING_IMPACTS.has(v.impact ?? ''),
  );
  expect(blocking.map(describe).join('\n\n'), 'axe violations').toBe('');
};
