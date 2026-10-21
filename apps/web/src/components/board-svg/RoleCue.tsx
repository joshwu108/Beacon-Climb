import type { HoldRole } from './geometry';

/** Badge letter per role: the text half of the non-colour cue (README.md). */
export const CUE_LETTER: Readonly<Record<HoldRole, string>> = {
  START: 'S',
  HAND: 'H',
  FOOT: 'f',
  FINISH: 'F',
};

/**
 * The role cue drawn over a lit hold, in viewBox units around (x, y): the ring shape
 * (START double, HAND solid, FOOT dashed, FINISH ring plus bar) and a lettered badge.
 * Colour comes from the role class (--led), so shape and letter work without it.
 */
export const RoleCue = ({
  role,
  x,
  y,
  r,
}: {
  role: HoldRole;
  x: number;
  y: number;
  r: number;
}) => {
  const w = r * 0.16;
  const badge = r * 0.62;
  const bx = x + r * 0.78;
  const by = y - r * 0.78;
  return (
    <>
      <circle
        className="cue__ring"
        cx={x}
        cy={y}
        r={role === 'FOOT' ? r * 0.86 : r}
        strokeWidth={w}
        strokeDasharray={role === 'FOOT' ? `${r * 0.42} ${r * 0.3}` : undefined}
      />
      {role === 'START' ? (
        <circle
          className="cue__ring"
          cx={x}
          cy={y}
          r={r + w * 2}
          strokeWidth={w * 0.75}
        />
      ) : null}
      {role === 'FINISH' ? (
        <line
          className="cue__bar"
          x1={x - r}
          x2={x + r}
          y1={y - r - w * 2.4}
          y2={y - r - w * 2.4}
          strokeWidth={w * 1.3}
        />
      ) : null}
      <circle className="cue__badge" cx={bx} cy={by} r={badge} />
      <text
        className="cue__letter"
        x={bx}
        y={by}
        fontSize={badge * 1.45}
        aria-hidden="true"
      >
        {CUE_LETTER[role]}
      </text>
    </>
  );
};
