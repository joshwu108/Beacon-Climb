import { describe, expect, it } from 'vitest';
import type { Attempt } from './draft-store';
import {
  FUTURE_MESSAGE,
  defaultValues,
  toAttemptCreate,
  toAttemptPatch,
  toLocalInputValue,
  validateAttempt,
  valuesFromAttempt,
} from './attempt-form';

const NOW = Date.parse('2026-10-14T14:00:00Z');
const at = (ms: number) => toLocalInputValue(new Date(ms));

const attempt: Attempt = {
  attempt_id: '018f0000-0000-7000-8000-000000000002',
  route_variant_id: 'synthetic-route-001:35',
  route: {
    route_variant_id: 'synthetic-route-001:35',
    board_id: 'synthetic-demo-1',
    angle_deg: 35,
    name: 'Synthetic Traverse 1',
    grade_numeric: 17,
    grade_label: 'V2',
    grade_observation_count: 0,
  },
  occurred_at: '2026-10-14T14:00:00Z',
  received_at: '2026-10-14T14:00:01Z',
  attempts_count: 2,
  sent: true,
  completion_fraction: null,
  perceived_effort: null,
  notes: null,
  revision: 1,
  status: 'ACTIVE',
};

describe('attempt form rules', () => {
  it('accepts up to five minutes in the future and rejects more (OCCURRED_AT_IN_FUTURE mirror)', () => {
    const base = defaultValues(new Date(NOW));
    expect(
      validateAttempt(
        { ...base, occurredAt: at(NOW + 4 * 60_000) },
        { now: NOW },
      ),
    ).toEqual({});
    expect(
      validateAttempt(
        { ...base, occurredAt: at(NOW + 6 * 60_000) },
        { now: NOW },
      ),
    ).toEqual({ occurred_at: FUTURE_MESSAGE });
    expect(
      validateAttempt({ ...base, occurredAt: '' }, { now: NOW }),
    ).toHaveProperty('occurred_at');
  });

  it('requires at least one attempt and bounds the optional answers', () => {
    const base = defaultValues(new Date(NOW));
    expect(
      validateAttempt({ ...base, attemptsCount: 0 }, { now: NOW }),
    ).toHaveProperty('attempts_count');
    expect(
      validateAttempt({ ...base, perceivedEffort: 11 }, { now: NOW }),
    ).toHaveProperty('perceived_effort');
    expect(
      validateAttempt({ ...base, notes: 'x'.repeat(501) }, { now: NOW }),
    ).toHaveProperty('notes');
    expect(
      validateAttempt({ ...base, notes: 'x'.repeat(500) }, { now: NOW }),
    ).toEqual({});
  });

  it('builds the AttemptCreate body, omitting untouched optional answers and progress when sent', () => {
    const base = defaultValues(new Date(NOW));
    expect(toAttemptCreate('r:35', base)).toEqual({
      route_variant_id: 'r:35',
      occurred_at: new Date(base.occurredAt).toISOString(),
      attempts_count: 1,
      sent: false,
    });
    expect(
      toAttemptCreate('r:35', {
        ...base,
        sent: false,
        completionPercent: 60,
        perceivedEffort: 7,
        notes: '  felt good  ',
      }),
    ).toMatchObject({
      completion_fraction: 0.6,
      perceived_effort: 7,
      notes: 'felt good',
    });
    expect(
      toAttemptCreate('r:35', { ...base, sent: true, completionPercent: 60 }),
    ).not.toHaveProperty('completion_fraction');
  });

  it('patches only the fields that changed', () => {
    const values = valuesFromAttempt(attempt);
    expect(toAttemptPatch(attempt, values)).toEqual({});
    expect(toAttemptPatch(attempt, { ...values, attemptsCount: 3 })).toEqual({
      attempts_count: 3,
    });
    expect(
      toAttemptPatch(attempt, {
        ...values,
        sent: false,
        completionPercent: 40,
        notes: 'slip',
      }),
    ).toEqual({ sent: false, completion_fraction: 0.4, notes: 'slip' });
  });
});
