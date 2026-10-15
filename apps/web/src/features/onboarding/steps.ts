import type { Me } from '../auth/useMe';
import { hasActiveConsent } from '../auth/useMe';

export const ONBOARDING_STEPS = [
  { id: 'account', label: 'Account', title: 'Choose a username' },
  { id: 'consent', label: 'Consent', title: 'Decide what Beacon may keep' },
  {
    id: 'board',
    label: 'Board and angle',
    title: 'Which board do you climb on?',
  },
  { id: 'goals', label: 'Level and goals', title: 'Where to start' },
] as const;

export type StepId = (typeof ONBOARDING_STEPS)[number]['id'];

export const STEP_IDS: readonly StepId[] = ONBOARDING_STEPS.map((s) => s.id);

export const isStepId = (value: string | undefined): value is StepId =>
  value !== undefined && (STEP_IDS as readonly string[]).includes(value);

export const stepIndex = (id: StepId): number => STEP_IDS.indexOf(id);

/** Choices collected on the board step and saved on the goals step. */
export interface PreferencesDraft {
  readonly boardId: string;
  readonly angleDeg: number;
}

/**
 * The first step that still needs doing, from the token and profile (resume logic).
 * `null` means setup is complete and the person belongs in Explore.
 */
export const firstIncompleteStep = (
  hasToken: boolean,
  me: Me | undefined,
): StepId | null => {
  if (!hasToken || me === undefined) return 'account';
  if (!hasActiveConsent(me)) return 'consent';
  if (me.preferences === null) return 'board';
  return null;
};

/**
 * Which step the URL may show. Completed steps are not revisited (the account step is
 * gone once signed in); the goals step is reachable only once a board draft exists.
 */
export const resolveStep = (
  requested: StepId | undefined,
  firstIncomplete: StepId,
  hasDraft: boolean,
): StepId => {
  if (requested === undefined) return firstIncomplete;
  if (stepIndex(requested) < stepIndex(firstIncomplete)) return firstIncomplete;
  if (requested === 'goals' && firstIncomplete === 'board') {
    return hasDraft ? 'goals' : 'board';
  }
  return stepIndex(requested) > stepIndex(firstIncomplete)
    ? firstIncomplete
    : requested;
};
