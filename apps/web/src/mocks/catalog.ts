/**
 * Deterministic synthetic catalog for the MSW listRoutes / getRoute handlers.
 *
 * contracts/examples/listRoutes/response.json holds a single route, which is not enough
 * to exercise filters or cursor paging in the explore screen (BC-0116). This module
 * derives a larger catalog from the committed fixtures: the fixture route stays item one
 * at its angle, and the rest are generated from the fixture Board (its holds and
 * available angles) with a fixed arithmetic so every run produces the same rows.
 *
 * Nothing here is a measurement: grades, observation counts and tags are synthetic and
 * only exist so the UI states (ungraded, low evidence, missing tags, paging) can be seen.
 */
import type { components } from '../api/generated/schema';
import listBoards from '../../../../contracts/examples/listBoards/response.json';
import listRoutes from '../../../../contracts/examples/listRoutes/response.json';
import getRoute from '../../../../contracts/examples/getRoute/response.json';

export type Board = components['schemas']['Board'];
export type RouteVariantSummary = components['schemas']['RouteVariantSummary'];
export type RouteVariant = components['schemas']['RouteVariant'];
export type Placement = components['schemas']['Placement'];
type HoldRole = Placement['role'];

export const FIXTURE_BOARD: Board = listBoards.body.items[0] as Board;
const FIXTURE_SUMMARY: RouteVariantSummary = listRoutes.body
  .items[0] as RouteVariantSummary;
const FIXTURE_ROUTE: RouteVariant = getRoute.body as RouteVariant;

/** Routes generated per available angle (the fixture route counts as one at 35). */
export const ROUTES_PER_ANGLE = 20;
export const DEFAULT_PAGE_LIMIT = 20;
export const MAX_PAGE_LIMIT = 100;
export const LOW_EVIDENCE_THRESHOLD = 10;

const NAME_STEMS = [
  'Traverse',
  'Crimp Line',
  'Sloper Arc',
  'Pinch Ladder',
  'Heel Hook',
  'Compression',
  'Mantle',
  'Edge Walk',
  'Cross Through',
  'Drop Knee',
] as const;

const ROLE_PATTERNS: readonly (readonly HoldRole[])[] = [
  ['START', 'HAND', 'FINISH'],
  ['START', 'FOOT', 'FINISH'],
  ['START', 'START', 'FINISH'],
  ['START', 'HAND', 'FOOT', 'FINISH'],
];

const SYNTHETIC_TAGS = ['crimpy', 'technical'] as const;

const pad3 = (n: number): string => String(n).padStart(3, '0');

/** Synthetic grade: 10..30 spread; every ninth seed is ungraded (null). */
const gradeFor = (seed: number): number | null =>
  seed % 9 === 0 ? null : 10 + ((seed * 7) % 21);

const placementsFor = (seed: number): Placement[] => {
  const pattern = ROLE_PATTERNS[seed % ROLE_PATTERNS.length];
  const holds = FIXTURE_BOARD.holds;
  return pattern.map((role, index) => {
    const hold = holds[index % holds.length];
    return { hold_id: hold.hold_id, x: hold.x, y: hold.y, role };
  });
};

/**
 * Route `n` set at `angle`. Ids follow the fixture convention `<route>:<angle>`, so
 * route 001 exists at every available angle and `001:35` is the committed fixture.
 * `seed` differs per angle so grades and counts are not identical across angles.
 */
const generateVariant = (
  n: number,
  angle: number,
  seed: number,
): RouteVariant => {
  const grade = gradeFor(seed);
  const hasTags = seed % 4 === 0;
  return {
    route_variant_id: `synthetic-route-${pad3(n)}:${angle}`,
    board_id: FIXTURE_BOARD.board_id,
    angle_deg: angle,
    name: `Synthetic ${NAME_STEMS[n % NAME_STEMS.length]} ${n}`,
    grade_numeric: grade,
    // The label-to-grade table (grade-scale.v1) is blocked, so generated routes carry
    // no label; only the committed fixture route has one.
    grade_label: null,
    grade_observation_count: grade === null ? 0 : (seed * 13) % 37,
    placements: placementsFor(seed),
    tags: hasTags ? [...SYNTHETIC_TAGS] : [],
    missing_tags: !hasTags,
  };
};

const buildCatalog = (): readonly RouteVariant[] =>
  FIXTURE_BOARD.available_angles.flatMap((angle, angleIndex) =>
    Array.from({ length: ROUTES_PER_ANGLE }, (_, i) => {
      const n = i + 1;
      const id = `synthetic-route-${pad3(n)}:${angle}`;
      return id === FIXTURE_ROUTE.route_variant_id
        ? FIXTURE_ROUTE
        : generateVariant(n, angle, angleIndex * ROUTES_PER_ANGLE + n);
    }),
  );

/** Every RouteVariant the mocks know about, in generation order. */
export const CATALOG: readonly RouteVariant[] = buildCatalog();

export const toSummary = (variant: RouteVariant): RouteVariantSummary => ({
  route_variant_id: variant.route_variant_id,
  board_id: variant.board_id,
  angle_deg: variant.angle_deg,
  name: variant.name,
  grade_numeric: variant.grade_numeric,
  grade_label: variant.grade_label,
  grade_observation_count: variant.grade_observation_count,
});

export const findVariant = (routeVariantId: string): RouteVariant | null =>
  CATALOG.find((v) => v.route_variant_id === routeVariantId) ?? null;

/* Cursor: base64url of "<grade_numeric or null>|<route_variant_id>" of the last item
   served. Opaque to clients; the handler re-derives the position from the sorted list. */
const toBase64Url = (text: string): string =>
  btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

const fromBase64Url = (text: string): string | null => {
  try {
    const padded = text.replace(/-/g, '+').replace(/_/g, '/');
    return atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  } catch {
    return null;
  }
};

export const encodeCursor = (item: RouteVariantSummary): string =>
  toBase64Url(`${item.grade_numeric ?? 'null'}|${item.route_variant_id}`);

export const decodeCursor = (
  cursor: string,
): { grade: number | null; routeVariantId: string } | null => {
  const decoded = fromBase64Url(cursor);
  if (decoded === null) return null;
  const separator = decoded.indexOf('|');
  if (separator <= 0) return null;
  const gradeText = decoded.slice(0, separator);
  const routeVariantId = decoded.slice(separator + 1);
  if (routeVariantId.length === 0) return null;
  if (gradeText === 'null') return { grade: null, routeVariantId };
  const grade = Number(gradeText);
  return Number.isFinite(grade) ? { grade, routeVariantId } : null;
};

/** Sort order of the list: grade ascending, ungraded last, then id for stability. */
const compareSummaries = (
  a: RouteVariantSummary,
  b: RouteVariantSummary,
): number => {
  if (a.grade_numeric === null && b.grade_numeric !== null) return 1;
  if (a.grade_numeric !== null && b.grade_numeric === null) return -1;
  if (
    a.grade_numeric !== null &&
    b.grade_numeric !== null &&
    a.grade_numeric !== b.grade_numeric
  ) {
    return a.grade_numeric - b.grade_numeric;
  }
  return a.route_variant_id < b.route_variant_id ? -1 : 1;
};

export interface RouteQuery {
  readonly boardId?: string;
  readonly angleDeg?: number;
  readonly gradeMin?: number;
  readonly gradeMax?: number;
  readonly cursor?: string;
  readonly limit: number;
}

export type RouteQueryResult =
  | {
      readonly kind: 'ok';
      readonly items: RouteVariantSummary[];
      readonly nextCursor: string | null;
    }
  | { readonly kind: 'invalid_cursor' };

const matchesGrade = (
  item: RouteVariantSummary,
  min: number | undefined,
  max: number | undefined,
): boolean => {
  if (min === undefined && max === undefined) return true;
  if (item.grade_numeric === null) return false;
  if (min !== undefined && item.grade_numeric < min) return false;
  if (max !== undefined && item.grade_numeric > max) return false;
  return true;
};

/** Apply the listRoutes query to the catalog: filter, sort, then page after the cursor. */
export const queryCatalog = (query: RouteQuery): RouteQueryResult => {
  const sorted = CATALOG.map(toSummary)
    .filter(
      (item) => query.boardId === undefined || item.board_id === query.boardId,
    )
    .filter(
      (item) =>
        query.angleDeg === undefined || item.angle_deg === query.angleDeg,
    )
    .filter((item) => matchesGrade(item, query.gradeMin, query.gradeMax))
    .sort(compareSummaries);

  let start = 0;
  if (query.cursor !== undefined) {
    const decoded = decodeCursor(query.cursor);
    const position =
      decoded === null
        ? -1
        : sorted.findIndex(
            (item) =>
              item.route_variant_id === decoded.routeVariantId &&
              item.grade_numeric === decoded.grade,
          );
    if (position < 0) return { kind: 'invalid_cursor' };
    start = position + 1;
  }

  const items = sorted.slice(start, start + query.limit);
  const last = items[items.length - 1];
  const hasMore = start + query.limit < sorted.length;
  return {
    kind: 'ok',
    items,
    nextCursor: hasMore && last !== undefined ? encodeCursor(last) : null,
  };
};

export { FIXTURE_SUMMARY, FIXTURE_ROUTE };
