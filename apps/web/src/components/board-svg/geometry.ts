import type { components } from '../../api/generated/schema';

export type BoardGeometry = components['schemas']['BoardGeometry'];
export type BoardHold = components['schemas']['BoardHold'];
export type Placement = components['schemas']['Placement'];
export type HoldRole = Placement['role'];

export const HOLD_ROLES: readonly HoldRole[] = [
  'START',
  'HAND',
  'FOOT',
  'FINISH',
];

/** The longer board side maps to this many viewBox units, so glyph sizes are stable. */
export const VIEWBOX_LONG_SIDE = 1000;
/** Padding around the board in viewBox units so edge holds and labels are not clipped. */
export const VIEWBOX_PAD = 110;

export interface ViewBox {
  readonly minX: number;
  readonly minY: number;
  readonly width: number;
  readonly height: number;
  readonly scale: number;
}

/** viewBox from the board geometry: width x height scaled, plus padding. */
export const viewBoxFor = (geometry: BoardGeometry): ViewBox => {
  const longSide = Math.max(geometry.width, geometry.height) || 1;
  const scale = VIEWBOX_LONG_SIDE / longSide;
  return {
    minX: -VIEWBOX_PAD,
    minY: -VIEWBOX_PAD,
    width: geometry.width * scale + 2 * VIEWBOX_PAD,
    height: geometry.height * scale + 2 * VIEWBOX_PAD,
    scale,
  };
};

export interface Point {
  readonly x: number;
  readonly y: number;
}

/**
 * Board coordinates -> SVG user units. SVG y grows downwards; when the board says
 * y_axis UP the value is flipped once here and nowhere else.
 */
export const toSvgPoint = (
  geometry: BoardGeometry,
  view: ViewBox,
  point: Point,
): Point => ({
  x: point.x * view.scale,
  y:
    (geometry.y_axis === 'UP' ? geometry.height - point.y : point.y) *
    view.scale,
});

export const ROLE_WORD: Readonly<Record<HoldRole, string>> = {
  START: 'Start',
  HAND: 'Hand',
  FOOT: 'Foot',
  FINISH: 'Finish',
};

const third = (
  value: number,
  max: number,
  low: string,
  mid: string,
  high: string,
): string => {
  const ratio = max === 0 ? 0 : value / max;
  return ratio < 1 / 3 ? low : ratio > 2 / 3 ? high : mid;
};

/** "lower left", "centre", "upper right" from the hold's place on the board (not the route). */
export const relativePosition = (
  geometry: BoardGeometry,
  point: Point,
): string => {
  const vertical = third(
    geometry.y_axis === 'UP' ? point.y : geometry.height - point.y,
    geometry.height,
    'lower',
    'middle',
    'upper',
  );
  const horizontal = third(point.x, geometry.width, 'left', 'centre', 'right');
  return vertical === 'middle' && horizontal === 'centre'
    ? 'centre'
    : `${vertical} ${horizontal}`;
};

const formatCoordinate = (value: number): string =>
  Number.isInteger(value) ? String(value) : value.toFixed(2);

/** One line per placement for the screen-reader list: "Start hold h1 at 0.25, 0.50 (middle left)". */
export const describePlacement = (
  geometry: BoardGeometry,
  placement: Placement,
): string =>
  `${ROLE_WORD[placement.role]} hold ${placement.hold_id} at ${formatCoordinate(placement.x)}, ${formatCoordinate(placement.y)} (${relativePosition(geometry, placement)})`;

export const countByRole = (
  placements: readonly Placement[],
): Readonly<Record<HoldRole, number>> =>
  placements.reduce(
    (counts, placement) => ({
      ...counts,
      [placement.role]: counts[placement.role] + 1,
    }),
    { START: 0, HAND: 0, FOOT: 0, FINISH: 0 } as Record<HoldRole, number>,
  );

/** "Board with 3 holds. Route uses 3: 1 start, 1 hand, 1 finish." */
export const summarise = (
  holdCount: number,
  placements: readonly Placement[],
  routeName?: string,
): string => {
  const counts = countByRole(placements);
  const parts = HOLD_ROLES.filter((role) => counts[role] > 0).map(
    (role) => `${counts[role]} ${ROLE_WORD[role].toLowerCase()}`,
  );
  const board = `Board with ${holdCount} hold${holdCount === 1 ? '' : 's'}.`;
  if (placements.length === 0) return `${board} No route drawn.`;
  const subject = routeName ? `Route ${routeName}` : 'Route';
  return `${board} ${subject} uses ${placements.length}: ${parts.join(', ')}.`;
};

/* Seeded hold looks. The contract only gives each hold an id and a position, so shape,
   size, rotation and resin colour are made up, but deterministically: the same hold_id
   always gets the same look, on every render and in every test. See README.md. */

/** FNV-1a: a 32-bit seed from a string. */
export const hashSeed = (text: string): number => {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i += 1) {
    hash = Math.imul(hash ^ text.charCodeAt(i), 0x01000193);
  }
  return hash >>> 0;
};

/** mulberry32: a small deterministic PRNG returning numbers in [0, 1). */
export const seededRandom = (seed: number): (() => number) => {
  let state = seed;
  return () => {
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
};

/** [width, height, roughness] of each silhouette: crimp, edge, jug, pinch, sloper,
    pocket, then two foot chips. Radii are jittered by a fixed seed per shape. */
const SHAPES = [
  [1.25, 0.68, 0.3],
  [1.4, 0.55, 0.25],
  [1.05, 0.95, 0.5],
  [0.68, 1.15, 0.3],
  [1.1, 1, 0.14],
  [1, 0.85, 0.55],
  [0.75, 0.6, 0.35],
  [0.8, 0.6, 0.45],
] as const;
const FOOT_CHIP = 6;
export const RESIN_COUNT = 6;
const POINTS = 9;
/** Each silhouette is pre-rotated to this many turns so its baked-in light (gradient and
    shadow, lit from the top left) stays put; the hold adds at most half a turn. */
export const HOLD_TURNS = 8;

const round = (value: number): number => Math.round(value * 1000) / 1000;

/** Closed smooth outline (unit radius) through jittered polar points, as an SVG path,
    turned by `turn` / HOLD_TURNS of a full circle. */
const shapePath = (
  [sx, sy, rough]: readonly number[],
  index: number,
  turn: number,
): string => {
  const next = seededRandom(index + 1);
  const t = (turn / HOLD_TURNS) * 2 * Math.PI;
  const points = Array.from({ length: POINTS }, (_, i) => {
    const angle = (i / POINTS) * 2 * Math.PI;
    const radius = 1 + rough * (next() - 0.5);
    const [x, y] = [
      sx * radius * Math.cos(angle),
      sy * radius * Math.sin(angle),
    ];
    return [
      x * Math.cos(t) - y * Math.sin(t),
      x * Math.sin(t) + y * Math.cos(t),
    ];
  });
  const mid = (i: number) => {
    const [a, b] = [points[i % POINTS], points[(i + 1) % POINTS]];
    return `${round((a[0] + b[0]) / 2)} ${round((a[1] + b[1]) / 2)}`;
  };
  return `M${mid(POINTS - 1)}${points
    .map(([x, y], i) => `Q${round(x)} ${round(y)} ${mid(i)}`)
    .join('')}Z`;
};

/** HOLD_SHAPE_PATHS[shape][turn]. */
export const HOLD_SHAPE_PATHS: readonly (readonly string[])[] = SHAPES.map(
  (shape, index) =>
    Array.from({ length: HOLD_TURNS }, (_, turn) =>
      shapePath(shape, index, turn),
    ),
);

export interface HoldLook {
  readonly shape: number;
  readonly rotation: number;
  /** Relative size, 1 = a full-size hold at this board's spacing. */
  readonly scale: number;
  readonly resin: number;
}

/**
 * The look of one hold from its id and its height on the board (rise: 0 bottom, 1 top).
 * Foot chips along the bottom, the biggest holds in the middle band.
 */
export const holdLook = (holdId: string, rise: number): HoldLook => {
  const next = seededRandom(hashSeed(holdId));
  const low = rise < 0.1;
  const band = 1 - Math.min(1, Math.abs(rise - 0.55) / 0.45);
  return {
    shape: Math.floor(next() * (low ? 2 : FOOT_CHIP)) + (low ? FOOT_CHIP : 0),
    rotation: Math.floor(next() * 360),
    scale: (low ? 0.6 : 0.7 + 0.28 * band) * (0.85 + 0.3 * next()),
    resin: Math.floor(next() * RESIN_COUNT),
  };
};

/** Typical distance between neighbouring holds in viewBox units, from the hold density. */
export const holdPitch = (
  view: ViewBox,
  geometry: BoardGeometry,
  count: number,
): number =>
  Math.min(
    72,
    Math.max(
      18,
      0.85 *
        Math.sqrt(
          (geometry.width * geometry.height * view.scale ** 2) /
            Math.max(count, 1),
        ),
    ),
  );

/** Smallest gap between distinct coordinates: the step of the T-nut grid. */
export const gridStep = (
  values: readonly number[],
  fallback: number,
): number => {
  const sorted = [...new Set(values.map((v) => Math.round(v)))].sort(
    (a, b) => a - b,
  );
  const gaps = sorted.slice(1).map((v, i) => v - sorted[i]);
  return gaps.length > 0 ? Math.max(8, Math.min(...gaps)) : fallback;
};
