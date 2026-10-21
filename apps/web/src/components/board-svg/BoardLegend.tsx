import { HOLD_ROLES, ROLE_WORD, type HoldRole } from './geometry';
import { RoleCue } from './RoleCue';

/** LED colour and cue shape per role, in words (README.md: never colour alone). */
const CUE_WORDS: Readonly<Record<HoldRole, string>> = {
  START: 'green, double ring, S',
  HAND: 'cyan, solid ring, H',
  FOOT: 'orange, dashed ring, f',
  FINISH: 'magenta, ring and bar, F',
};

/** Key for the board: each role's LED and cue as drawn on the board, plus words. */
export const BoardLegend = () => (
  <div className="legend" role="group" aria-label="Hold roles">
    <dl className="legend__list">
      {HOLD_ROLES.map((role) => (
        <div className="legend__row" key={role}>
          <dt>
            <svg
              viewBox="0 0 40 40"
              aria-hidden="true"
              className={`legend__glyph role--${role.toLowerCase()}`}
            >
              <RoleCue role={role} x={20} y={21} r={11} />
            </svg>
            <span>{ROLE_WORD[role]}</span>
          </dt>
          <dd>{CUE_WORDS[role]}</dd>
        </div>
      ))}
    </dl>
  </div>
);
