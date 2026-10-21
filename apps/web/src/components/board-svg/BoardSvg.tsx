import { useId, useMemo } from 'react';
import type { components } from '../../api/generated/schema';
import {
  describePlacement,
  gridStep,
  holdLook,
  holdPitch,
  HOLD_SHAPE_PATHS,
  HOLD_TURNS,
  summarise,
  toSvgPoint,
  viewBoxFor,
  type BoardGeometry,
  type HoldRole,
  type Placement,
  type Point,
} from './geometry';
import { RoleCue } from './RoleCue';
import './board-svg.css';

export type BoardShape = Pick<
  components['schemas']['Board'],
  'holds' | 'geometry'
>;

export interface BoardSvgProps {
  readonly board: BoardShape;
  readonly placements?: readonly Placement[];
  /** Draw this role at full strength and the others dimmed (shape and text stay). */
  readonly highlightRole?: HoldRole;
  /** CSS width; the height follows the viewBox aspect ratio. */
  readonly size?: number | string;
  /** Route name for the accessible summary. */
  readonly routeName?: string;
  readonly className?: string;
}

/** Board layout in viewBox units, computed once per board. */
const layoutFor = (board: BoardShape) => {
  const { geometry, holds } = board;
  const view = viewBoxFor(geometry);
  const pitch = holdPitch(view, geometry, holds.length);
  const w = geometry.width * view.scale;
  const h = geometry.height * view.scale;
  const points = holds.map((hold) => toSvgPoint(geometry, view, hold));
  const xs = points.map((p) => p.x);
  const ys = points.map((p) => p.y);
  // Margin around the holds; with the kickboard and frame it stays inside VIEWBOX_PAD.
  const m = Math.min(36, pitch * 0.75);
  return {
    view,
    pitch,
    panel: { x: -m, y: -m, w: w + 2 * m, h: h + 2 * m },
    kick: Math.min(60, Math.max(30, pitch * 1.3)),
    step: { x: gridStep(xs, pitch), y: gridStep(ys, pitch) },
    origin: points[0] ?? { x: 0, y: 0 },
    points,
  };
};

/** translate/rotate/scale for one hold silhouette, and its resin and shape. */
const placeHold = (
  geometry: BoardGeometry,
  holdId: string,
  board: Point,
  at: Point,
  pitch: number,
) => {
  const rise = geometry.y_axis === 'UP' ? board.y : geometry.height - board.y;
  const look = holdLook(holdId, geometry.height ? rise / geometry.height : 0);
  const step = 360 / HOLD_TURNS;
  const turn = Math.round(look.rotation / step);
  return {
    href: `${look.shape}-${turn % HOLD_TURNS}`,
    className: `hold--r${look.resin}`,
    transform: `translate(${at.x} ${at.y}) rotate(${look.rotation - turn * step}) scale(${look.scale * pitch * 0.5})`,
  };
};

/**
 * A training board drawn as a photo: a dark textured panel with T-nut holes, sheet
 * seams and a kickboard; a seeded silhouette at every hold (geometry.ts); and, for a
 * route, LED glows with a non-colour role cue (RoleCue) and a <title> per placement.
 * A visually hidden ordered list of placements is referenced by aria-describedby.
 */
export const BoardSvg = ({
  board,
  placements = [],
  highlightRole,
  size = '100%',
  routeName,
  className,
}: BoardSvgProps) => {
  const id = useId();
  const listId = `${id}-placements`;
  const ref = id.replace(/[^\w-]/g, '');
  const url = (name: string) => `url(#${ref}${name})`;
  const layout = useMemo(() => layoutFor(board), [board]);
  const { view, pitch, panel, kick, step, origin } = layout;
  const label = summarise(board.holds.length, placements, routeName);
  const lit = placements.length > 0;
  const cue = Math.min(72, Math.max(34, pitch)) * 0.62;
  /* Shadow (down and right), resin body, top-left light, bolt: lit from the top left. */
  const shapes = HOLD_SHAPE_PATHS.flatMap((turns, i) =>
    turns.map((d, turn) => (
      <g id={`${ref}s${i}-${turn}`} key={d}>
        <path
          d={d}
          transform="translate(.12 .2)"
          style={{ fill: 'var(--board-hold-shadow)' }}
        />
        <path d={d} fill="currentColor" />
        <path d={d} fill={url('shade')} />
        <circle r={0.13} style={{ fill: 'var(--board-bolt)' }} />
      </g>
    )),
  );
  const lights = placements.map((placement, index) => {
    const at = toSvgPoint(board.geometry, view, placement);
    const dim = highlightRole !== undefined && highlightRole !== placement.role;
    return {
      key: `${placement.hold_id}-${index}`,
      placement,
      at,
      role: `role--${placement.role.toLowerCase()}${dim ? ' is-dim' : ''}`,
      text: describePlacement(board.geometry, placement),
    };
  });
  const roleOf = new Map(
    lights.map((light) => [light.placement.hold_id, light.role]),
  );
  /** One hold silhouette; placed holds are drawn above the dark-gym scrim. */
  const holdUse = (hold: BoardShape['holds'][number], i: number) => {
    const look = placeHold(
      board.geometry,
      hold.hold_id,
      hold,
      layout.points[i],
      pitch,
    );
    const role = roleOf.get(hold.hold_id);
    return (
      <use
        key={hold.hold_id}
        href={`#${ref}s${look.href}`}
        transform={look.transform}
        className={`hold ${look.className}${role ? ` hold--placed ${role}` : ''}`}
        data-hold-id={hold.hold_id}
      />
    );
  };
  const bottom = panel.y + panel.h;
  const flare = panel.w * 0.012;

  return (
    <figure
      className={`board${lit ? ' board--lit' : ''}${className ? ` ${className}` : ''}`}
      style={{ width: size }}
    >
      <svg
        className="board__svg"
        role="img"
        aria-label={label}
        aria-describedby={lit ? listId : undefined}
        viewBox={`${view.minX} ${view.minY} ${view.width} ${view.height}`}
      >
        <defs>
          {shapes}
          <pattern
            id={`${ref}tnut`}
            patternUnits="userSpaceOnUse"
            x={origin.x - step.x / 2}
            y={origin.y - step.y / 2}
            width={step.x}
            height={step.y}
          >
            <circle
              className="board__tnut"
              cx={step.x / 2}
              cy={step.y / 2}
              r={Math.min(step.x, step.y, pitch) * 0.09}
            />
          </pattern>
          <radialGradient id={`${ref}vignette`} r="0.75">
            <stop offset="0.45" className="board__vignette-in" />
            <stop offset="1" className="board__vignette-out" />
          </radialGradient>
          <linearGradient id={`${ref}shade`} x2="1" y2="1">
            <stop offset="0" className="board__shade-hi" />
            <stop offset="0.45" className="board__shade-mid" />
            <stop offset="1" className="board__shade-lo" />
          </linearGradient>
          <filter
            id={`${ref}bloom`}
            x="-50%"
            y="-50%"
            width="200%"
            height="200%"
          >
            <feGaussianBlur stdDeviation={cue * 0.5} />
          </filter>
        </defs>

        <rect
          className="board__frame"
          x={panel.x - 10}
          y={panel.y - 10}
          width={panel.w + 20}
          height={panel.h + kick + 20}
          rx={8}
        />
        <rect
          className="board__face"
          x={panel.x}
          y={panel.y}
          width={panel.w}
          height={panel.h}
        />
        <path
          className="board__kick"
          d={`M${panel.x} ${bottom}h${panel.w}l${flare} ${kick}h${-panel.w - 2 * flare}z`}
        />
        <rect
          fill={url('tnut')}
          x={panel.x}
          y={panel.y}
          width={panel.w}
          height={panel.h}
        />
        <path
          className="board__seam"
          d={`M${panel.x + panel.w / 3} ${panel.y}v${panel.h}M${panel.x + (2 * panel.w) / 3} ${panel.y}v${panel.h}M${panel.x} ${panel.y + panel.h / 3}h${panel.w}`}
        />

        <g className="board__holds">
          {board.holds.map((hold, i) =>
            roleOf.has(hold.hold_id) ? null : holdUse(hold, i),
          )}
        </g>

        <rect
          className="board__vignette"
          fill={url('vignette')}
          x={panel.x}
          y={panel.y}
          width={panel.w}
          height={panel.h + kick}
        />

        {lit ? (
          <>
            <g className="board__bloom" filter={url('bloom')}>
              {lights.map((light) => (
                <circle
                  key={light.key}
                  className={`led ${light.role}`}
                  cx={light.at.x}
                  cy={light.at.y}
                  r={cue * 1.15}
                />
              ))}
            </g>
            <g className="board__lit">
              {board.holds.map((hold, i) =>
                roleOf.has(hold.hold_id) ? holdUse(hold, i) : null,
              )}
            </g>
            <g className="board__placements">
              {lights.map(({ key, placement, at, role, text }) => (
                <g
                  key={key}
                  className={`marker ${role}`}
                  data-role={placement.role}
                >
                  <title>{text}</title>
                  <RoleCue role={placement.role} x={at.x} y={at.y} r={cue} />
                </g>
              ))}
            </g>
          </>
        ) : null}
      </svg>
      {lit ? (
        <ol className="visually-hidden" id={listId}>
          {lights.map(({ key, text }) => (
            <li key={key}>{text}</li>
          ))}
        </ol>
      ) : null}
    </figure>
  );
};
