import type { RouteVariantSummary } from './useRoutes';

/** Below this many logged observations the grade is flagged as low evidence (06 §9 min_obs is not exposed; 10 is the UI's own threshold). */
export const LOW_EVIDENCE_THRESHOLD = 10;

export const isLowEvidence = (
  route: Pick<RouteVariantSummary, 'grade_observation_count'>,
): boolean => route.grade_observation_count < LOW_EVIDENCE_THRESHOLD;

/** Numeric grade for display; the label table is blocked, so numeric is primary. */
export const formatGrade = (
  route: Pick<RouteVariantSummary, 'grade_numeric' | 'grade_label'>,
): {
  readonly primary: string;
  readonly label: string | null;
  readonly isUngraded: boolean;
} =>
  route.grade_numeric === null
    ? { primary: 'ungraded', label: null, isUngraded: true }
    : {
        primary: Number.isInteger(route.grade_numeric)
          ? String(route.grade_numeric)
          : route.grade_numeric.toFixed(1),
        label: route.grade_label,
        isUngraded: false,
      };

export const formatObservations = (count: number): string =>
  `${count} observation${count === 1 ? '' : 's'}`;
