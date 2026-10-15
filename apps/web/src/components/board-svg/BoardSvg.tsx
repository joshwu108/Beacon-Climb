import { useId } from 'react';
import type { components } from '../../api/generated/schema';
import {
  describePlacement,
  summarise,
  toSvgPoint,
  viewBoxFor,
  type HoldRole,
  type Placement,
} from './geometry';
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

/* Glyph sizes in viewBox units (long side = 1000). Shapes, not colour, tell roles apart:
   START double ring, HAND filled circle, FOOT small triangle, FINISH ring with a bar. */
const R = 22;
const RING_GAP = 8;
const FOOT = 20;
const FINISH_BAR = 14;
const NEUTRAL_R = 9;
const LABEL_OFFSET = R + RING_GAP + 16;

const Marker = ({
  role,
  x,
  y,
  dimmed,
  title,
}: {
  role: HoldRole;
  x: number;
  y: number;
  dimmed: boolean;
  title: string;
}) => {
  const className = `marker marker--${role.toLowerCase()}${dimmed ? ' marker--dim' : ''}`;
  switch (role) {
    case 'START':
      return (
        <g className={className} data-role={role}>
          <title>{title}</title>
          <circle cx={x} cy={y} r={R} className="marker__fill" />
          <circle cx={x} cy={y} r={R + RING_GAP} className="marker__ring" />
          <text
            x={x}
            y={y - LABEL_OFFSET}
            className="marker__label"
            aria-hidden="true"
          >
            START
          </text>
        </g>
      );
    case 'HAND':
      return (
        <g className={className} data-role={role}>
          <title>{title}</title>
          <circle cx={x} cy={y} r={R} className="marker__fill" />
        </g>
      );
    case 'FOOT':
      return (
        <g className={className} data-role={role}>
          <title>{title}</title>
          <polygon
            points={`${x},${y - FOOT} ${x + FOOT},${y + FOOT * 0.8} ${x - FOOT},${y + FOOT * 0.8}`}
            className="marker__fill"
          />
        </g>
      );
    case 'FINISH':
      return (
        <g className={className} data-role={role}>
          <title>{title}</title>
          <circle cx={x} cy={y} r={R} className="marker__ring" />
          <line
            x1={x - R - 4}
            x2={x + R + 4}
            y1={y - R - FINISH_BAR}
            y2={y - R - FINISH_BAR}
            className="marker__bar"
          />
          <text
            x={x}
            y={y - LABEL_OFFSET - 6}
            className="marker__label"
            aria-hidden="true"
          >
            FINISH
          </text>
        </g>
      );
  }
};

/**
 * Pure board renderer: every hold as a muted dot, placements as role-shaped markers
 * with a <title> each, and a visually hidden ordered list of placements that the SVG
 * references through aria-describedby. No animation, so reduced motion needs nothing.
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
  const view = viewBoxFor(board.geometry);
  const placedIds = new Set(placements.map((p) => p.hold_id));
  const label = summarise(board.holds.length, placements, routeName);

  return (
    <figure
      className={`board${className ? ` ${className}` : ''}`}
      style={{ width: size }}
    >
      <svg
        className="board__svg"
        role="img"
        aria-label={label}
        aria-describedby={placements.length > 0 ? listId : undefined}
        viewBox={`${view.minX} ${view.minY} ${view.width} ${view.height}`}
        preserveAspectRatio="xMidYMid meet"
        focusable="false"
      >
        <rect
          className="board__face"
          x={0}
          y={0}
          width={board.geometry.width * view.scale}
          height={board.geometry.height * view.scale}
          rx={12}
        />
        <g className="board__holds">
          {board.holds.map((hold) => {
            const p = toSvgPoint(board.geometry, view, hold);
            return (
              <circle
                key={hold.hold_id}
                cx={p.x}
                cy={p.y}
                r={NEUTRAL_R}
                className={`hold${placedIds.has(hold.hold_id) ? ' hold--placed' : ''}`}
                data-hold-id={hold.hold_id}
              />
            );
          })}
        </g>
        <g className="board__placements">
          {placements.map((placement, index) => {
            const p = toSvgPoint(board.geometry, view, placement);
            return (
              <Marker
                key={`${placement.hold_id}-${placement.role}-${index}`}
                role={placement.role}
                x={p.x}
                y={p.y}
                dimmed={
                  highlightRole !== undefined &&
                  highlightRole !== placement.role
                }
                title={describePlacement(board.geometry, placement)}
              />
            );
          })}
        </g>
      </svg>
      {placements.length > 0 ? (
        <ol className="visually-hidden" id={listId}>
          {placements.map((placement, index) => (
            <li key={`${placement.hold_id}-${index}`}>
              {describePlacement(board.geometry, placement)}
            </li>
          ))}
        </ol>
      ) : null}
    </figure>
  );
};
