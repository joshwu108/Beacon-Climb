import { describe, expect, it } from 'vitest';
import listRoutesFixture from '../../../../contracts/examples/listRoutes/response.json';
import getRouteFixture from '../../../../contracts/examples/getRoute/response.json';
import {
  CATALOG,
  FIXTURE_BOARD,
  ROUTES_PER_ANGLE,
  decodeCursor,
  encodeCursor,
  findVariant,
  queryCatalog,
} from './catalog';

describe('synthetic catalog', () => {
  it('generates ROUTES_PER_ANGLE variants per available angle, deterministically', () => {
    expect(CATALOG).toHaveLength(
      FIXTURE_BOARD.available_angles.length * ROUTES_PER_ANGLE,
    );
    const ids = CATALOG.map((v) => v.route_variant_id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const angle of FIXTURE_BOARD.available_angles) {
      expect(CATALOG.filter((v) => v.angle_deg === angle)).toHaveLength(
        ROUTES_PER_ANGLE,
      );
    }
  });

  it('keeps the committed fixture route verbatim', () => {
    expect(findVariant(getRouteFixture.body.route_variant_id)).toEqual(
      getRouteFixture.body,
    );
    const page = queryCatalog({ angleDeg: 40, limit: 100 });
    expect(page.kind === 'ok' && page.items).toContainEqual(
      listRoutesFixture.body.items[0],
    );
  });

  it('every placement references a hold on the fixture board', () => {
    const holdIds = new Set(FIXTURE_BOARD.holds.map((h) => h.hold_id));
    for (const variant of CATALOG) {
      expect(variant.placements.length).toBeGreaterThanOrEqual(3);
      for (const placement of variant.placements) {
        expect(holdIds.has(placement.hold_id)).toBe(true);
      }
    }
  });

  it('includes ungraded, low-evidence and tagged routes so the UI states exist', () => {
    expect(CATALOG.some((v) => v.grade_numeric === null)).toBe(true);
    expect(CATALOG.some((v) => v.grade_observation_count < 10)).toBe(true);
    expect(CATALOG.some((v) => v.grade_observation_count >= 10)).toBe(true);
    expect(CATALOG.some((v) => !v.missing_tags && v.tags.length > 0)).toBe(
      true,
    );
  });

  it('pages with an opaque cursor and never repeats or skips a row', () => {
    const seen: string[] = [];
    let cursor: string | undefined;
    for (let guard = 0; guard < 10; guard += 1) {
      const page = queryCatalog({ angleDeg: 40, limit: 7, cursor });
      expect(page.kind).toBe('ok');
      if (page.kind !== 'ok') return;
      seen.push(...page.items.map((i) => i.route_variant_id));
      if (page.nextCursor === null) break;
      cursor = page.nextCursor;
    }
    expect(seen).toHaveLength(ROUTES_PER_ANGLE);
    expect(new Set(seen).size).toBe(ROUTES_PER_ANGLE);
  });

  it('sorts by grade ascending with ungraded routes last', () => {
    const page = queryCatalog({ angleDeg: 45, limit: 100 });
    if (page.kind !== 'ok') throw new Error('expected ok');
    const grades = page.items.map((i) => i.grade_numeric);
    const graded = grades.filter((g): g is number => g !== null);
    expect([...graded].sort((a, b) => a - b)).toEqual(graded);
    const firstNull = grades.indexOf(null);
    expect(
      firstNull === -1 || grades.slice(firstNull).every((g) => g === null),
    ).toBe(true);
  });

  it('applies grade_min / grade_max and excludes ungraded routes when a bound is set', () => {
    const page = queryCatalog({ gradeMin: 15, gradeMax: 20, limit: 100 });
    if (page.kind !== 'ok') throw new Error('expected ok');
    expect(page.items.length).toBeGreaterThan(0);
    for (const item of page.items) {
      expect(item.grade_numeric).not.toBeNull();
      expect(item.grade_numeric!).toBeGreaterThanOrEqual(15);
      expect(item.grade_numeric!).toBeLessThanOrEqual(20);
    }
  });

  it('cursor is base64url of "grade|id" and rejects garbage', () => {
    const cursor = encodeCursor(listRoutesFixture.body.items[0]);
    expect(cursor).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(decodeCursor(cursor)).toEqual({
      grade: 17,
      routeVariantId: 'synthetic-route-001:40',
    });
    expect(decodeCursor('***')).toBeNull();
    expect(decodeCursor(btoa('no-separator'))).toBeNull();
    expect(queryCatalog({ cursor: '***', limit: 10 })).toEqual({
      kind: 'invalid_cursor',
    });
  });
});
