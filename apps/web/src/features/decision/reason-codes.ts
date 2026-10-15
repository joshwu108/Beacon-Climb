import type { components } from '../../api/generated/schema';

export type ReasonCode = components['schemas']['ReasonCode'];
export type UncertaintyLabel = components['schemas']['UncertaintyLabel'];

export interface ReasonCopy {
  /** Short chip text. */
  readonly label: string;
  /** One sentence on why the route is in the list. Never a prediction about the person. */
  readonly explanation: string;
}

/**
 * Copy for every ReasonCode (wireframes/session-builder.md, "Reason-code copy"). The
 * `satisfies` clause fails the build if the contract adds or renames a code without
 * copy; reason-codes.test.ts checks the same against the generated schema text.
 */
export const REASON_CODES = {
  COMPATIBLE_ANGLE: {
    label: 'Matches your board and angle',
    explanation: 'This route exists on your board at your selected angle.',
  },
  GOAL_CHALLENGE_MATCH: {
    label: 'Grade fits your goal',
    explanation:
      'Its grade is close to the target grade the policy uses for your goal and starting band.',
  },
  NOVEL_ROUTE: {
    label: 'New to you',
    explanation: "It isn't among your recently logged routes.",
  },
  PREFERRED_STYLE: {
    label: 'Matches your goals',
    explanation: 'It matches a preference taken from your selected goals.',
  },
  WELL_OBSERVED_GRADE: {
    label: 'Grade is well observed',
    explanation:
      'Its community grade is backed by a good number of logged observations.',
  },
  COOLDOWN_SKIPPED: {
    label: 'Recent routes left out',
    explanation:
      "Routes you attempted within the policy's repeat-avoidance window were not considered.",
  },
  LOW_INVENTORY_WIDENED: {
    label: 'Few routes matched',
    explanation:
      'Not many routes fit, so the grade window was widened to fill the list.',
  },
  COLD_START_HEURISTIC: {
    label: 'Starting pick',
    explanation:
      "Chosen from grade and your goal because there isn't enough logged history yet.",
  },
} as const satisfies Record<ReasonCode, ReasonCopy>;

export const REASON_CODE_VALUES = Object.keys(REASON_CODES) as ReasonCode[];

const isKnownReason = (code: string): code is ReasonCode =>
  Object.hasOwn(REASON_CODES, code);

/** Copy for a code; a code the dictionary lacks is printed as text, never hidden. */
export const describeReasonCode = (code: string): ReasonCopy =>
  isKnownReason(code)
    ? REASON_CODES[code]
    : { label: `Other reason: ${code}`, explanation: '' };

const UNCERTAINTY_LABELS: Record<UncertaintyLabel, string> = {
  INSUFFICIENT_HISTORY: 'Not enough history',
  LOW: 'Low confidence',
  MEDIUM: 'Medium confidence',
  HIGH: 'High confidence',
};

export const describeUncertainty = (label: string): string =>
  Object.hasOwn(UNCERTAINTY_LABELS, label)
    ? UNCERTAINTY_LABELS[label as UncertaintyLabel]
    : label;

/** `rank_score` is a relative ordering value; shown with three decimals and never as a chance. */
export const formatRankScore = (score: number): string => score.toFixed(3);

export const describeModelVersion = (version: string): string =>
  version === 'none' ? 'none (no learned model in use)' : version;

export const NO_ESTIMATE_COPY = 'No personalised estimate yet';
