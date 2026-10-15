import { describe, expect, it } from 'vitest';
import {
  parseFilters,
  toRoutesQuery,
  toSearchParams,
  validateFilters,
  type ExploreDefaults,
} from './filters';

const defaults: ExploreDefaults = {
  angleDeg: 35,
  availableAngles: [20, 35, 45],
};

describe('explore filters', () => {
  it('parses the URL and falls back to the preference angle when the board lacks the requested one', () => {
    expect(parseFilters(new URLSearchParams(''), defaults)).toEqual({
      angleDeg: 35,
      gradeMin: null,
      gradeMax: null,
    });
    expect(
      parseFilters(
        new URLSearchParams('angle_deg=45&grade_min=12&grade_max=18'),
        defaults,
      ),
    ).toEqual({ angleDeg: 45, gradeMin: 12, gradeMax: 18 });
    expect(
      parseFilters(new URLSearchParams('angle_deg=50&grade_min=abc'), defaults),
    ).toEqual({
      angleDeg: 35,
      gradeMin: null,
      gradeMax: null,
    });
  });

  it('writes only non-default values back to the URL', () => {
    expect(
      toSearchParams(
        { angleDeg: 35, gradeMin: null, gradeMax: null },
        defaults,
      ).toString(),
    ).toBe('');
    expect(
      toSearchParams(
        { angleDeg: 20, gradeMin: 10, gradeMax: null },
        defaults,
      ).toString(),
    ).toBe('angle_deg=20&grade_min=10');
  });

  it('blocks from > to client-side', () => {
    expect(validateFilters({ angleDeg: 35, gradeMin: 20, gradeMax: 10 })).toBe(
      'Grade from must not be higher than grade to.',
    );
    expect(
      validateFilters({ angleDeg: 35, gradeMin: 10, gradeMax: 10 }),
    ).toBeNull();
    expect(
      validateFilters({ angleDeg: 35, gradeMin: null, gradeMax: 10 }),
    ).toBeNull();
  });

  it('builds the listRoutes query with board_id and angle_deg always present', () => {
    expect(
      toRoutesQuery('synthetic-demo-1', {
        angleDeg: 45,
        gradeMin: null,
        gradeMax: 22,
      }),
    ).toEqual({
      board_id: 'synthetic-demo-1',
      angle_deg: 45,
      grade_max: 22,
      limit: 10,
    });
  });
});
