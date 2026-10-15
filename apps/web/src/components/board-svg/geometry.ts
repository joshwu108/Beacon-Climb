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
