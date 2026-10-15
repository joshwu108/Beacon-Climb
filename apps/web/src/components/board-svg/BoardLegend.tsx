import { HOLD_ROLES, ROLE_WORD, type HoldRole } from './geometry';

const SHAPE_WORD: Readonly<Record<HoldRole, string>> = {
  START: 'double ring',
  HAND: 'filled circle',
  FOOT: 'small triangle',
  FINISH: 'ring with a bar',
};

const Glyph = ({ role }: { role: HoldRole }) => {
  switch (role) {
    case 'START':
      return (
        <svg
          viewBox="0 0 40 40"
          aria-hidden="true"
          className={`legend__glyph marker--${role.toLowerCase()}`}
        >
          <circle cx="20" cy="20" r="9" className="marker__fill" />
          <circle cx="20" cy="20" r="15" className="marker__ring" />
        </svg>
      );
    case 'HAND':
      return (
        <svg
          viewBox="0 0 40 40"
          aria-hidden="true"
          className={`legend__glyph marker--${role.toLowerCase()}`}
        >
          <circle cx="20" cy="20" r="11" className="marker__fill" />
        </svg>
      );
    case 'FOOT':
      return (
        <svg
          viewBox="0 0 40 40"
          aria-hidden="true"
          className={`legend__glyph marker--${role.toLowerCase()}`}
        >
          <polygon points="20,8 32,30 8,30" className="marker__fill" />
        </svg>
      );
    case 'FINISH':
      return (
        <svg
          viewBox="0 0 40 40"
          aria-hidden="true"
          className={`legend__glyph marker--${role.toLowerCase()}`}
        >
          <circle cx="20" cy="24" r="10" className="marker__ring" />
          <line x1="6" x2="34" y1="7" y2="7" className="marker__bar" />
        </svg>
      );
  }
};

/** Shape key for the board: each role in words next to its glyph (never colour alone). */
export const BoardLegend = () => (
  <div className="legend" role="group" aria-label="Hold roles">
    <dl className="legend__list">
      {HOLD_ROLES.map((role) => (
        <div className="legend__row" key={role}>
          <dt>
            <Glyph role={role} />
            <span>{ROLE_WORD[role]}</span>
          </dt>
          <dd>{SHAPE_WORD[role]}</dd>
        </div>
      ))}
    </dl>
  </div>
);
