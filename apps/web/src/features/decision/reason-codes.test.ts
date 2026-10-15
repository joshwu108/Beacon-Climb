// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  REASON_CODES,
  REASON_CODE_VALUES,
  describeModelVersion,
  describeReasonCode,
  describeUncertainty,
  formatRankScore,
} from './reason-codes';

/** The ReasonCode union as written in the generated schema (openapi-typescript output). */
const schemaReasonCodes = (): string[] => {
  const text = readFileSync(
    new URL('../../api/generated/schema.d.ts', import.meta.url),
    'utf8',
  );
  const match = text.match(/ReasonCode: ([^;]+);/);
  if (match === null) throw new Error('ReasonCode not found in schema.d.ts');
  return [...match[1]!.matchAll(/"([A-Z_]+)"/g)].map((m) => m[1]!);
};

describe('reason-code dictionary', () => {
  it('has a label and explanation for every ReasonCode in the generated schema', () => {
    const codes = schemaReasonCodes();
    expect(codes).toHaveLength(8);
    for (const code of codes) {
      const copy = REASON_CODES[code as keyof typeof REASON_CODES];
      expect(copy, code).toBeDefined();
      expect(copy.label.length, code).toBeGreaterThan(0);
      expect(copy.explanation.length, code).toBeGreaterThan(0);
    }
    expect(new Set(REASON_CODE_VALUES)).toEqual(new Set(codes));
  });

  it('never predicts an outcome for the person', () => {
    for (const copy of Object.values(REASON_CODES)) {
      expect(`${copy.label} ${copy.explanation}`).not.toMatch(
        /you will|chance|likely to send|you can/i,
      );
    }
  });

  it('prints an unknown code as text instead of hiding it', () => {
    expect(describeReasonCode('FUTURE_CODE')).toEqual({
      label: 'Other reason: FUTURE_CODE',
      explanation: '',
    });
  });

  it('formats scores with three decimals and labels versions', () => {
    expect(formatRankScore(0.72)).toBe('0.720');
    expect(describeUncertainty('INSUFFICIENT_HISTORY')).toBe(
      'Not enough history',
    );
    expect(describeUncertainty('ODD')).toBe('ODD');
    expect(describeModelVersion('none')).toBe('none (no learned model in use)');
    expect(describeModelVersion('ranker-v2')).toBe('ranker-v2');
  });
});
