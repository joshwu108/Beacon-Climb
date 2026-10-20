import type { components } from '../../api/generated/schema';
import { hasActiveConsent, type Me } from '../auth/useMe';

export type GradeBand = components['schemas']['GradeBand'];

/** Same four steps and resume rules as apps/web (features/onboarding/steps.ts). */
export const ONBOARDING_STEPS = [
  { id: 'account', label: 'Account', title: 'Choose a username' },
  { id: 'consent', label: 'Consent', title: 'Decide what Beacon may keep' },
  { id: 'board', label: 'Board', title: 'Which board do you climb on?' },
  { id: 'goals', label: 'Goals', title: 'Where to start' },
] as const;

export type StepId = (typeof ONBOARDING_STEPS)[number]['id'];

/** The first step that still needs doing; null when setup is complete. */
export const firstIncompleteStep = (
  hasToken: boolean,
  me: Me | undefined,
): StepId | null => {
  if (!hasToken || me === undefined) return 'account';
  if (!hasActiveConsent(me)) return 'consent';
  if (me.preferences === null) return 'board';
  return null;
};

export const stepIndex = (id: StepId): number =>
  ONBOARDING_STEPS.findIndex((step) => step.id === id);

/* Plain-language starting bands (apps/web GoalsStep): they describe the routes shown
   first, never the person. */
export const GRADE_BANDS: readonly {
  id: GradeBand;
  label: string;
  hint: string;
}[] = [
  {
    id: 'NOVICE',
    label: 'Novice',
    hint: 'New to board climbing. Start with the easiest routes.',
  },
  {
    id: 'BEGINNER',
    label: 'Beginner',
    hint: 'A few sessions in. Start on the lower grades.',
  },
  {
    id: 'INTERMEDIATE',
    label: 'Intermediate',
    hint: 'Start on the board’s middle grades.',
  },
  { id: 'ADVANCED', label: 'Advanced', hint: 'Start on the upper grades.' },
  {
    id: 'EXPERT',
    label: 'Expert',
    hint: 'Start on the hardest routes the board has.',
  },
];

export const MAX_GOALS = 4;

export const USERNAME_RE = /^[a-z0-9_]{3,32}$/;
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 128;

export const validateAccount = (
  username: string,
  password: string,
): { username?: string; password?: string } => ({
  ...(USERNAME_RE.test(username)
    ? {}
    : { username: 'Use 3 to 32 lowercase letters, digits or underscores.' }),
  ...(password.length >= PASSWORD_MIN && password.length <= PASSWORD_MAX
    ? {}
    : { password: `Use ${PASSWORD_MIN} to ${PASSWORD_MAX} characters.` }),
});
