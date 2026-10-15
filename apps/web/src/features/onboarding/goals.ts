import type { components } from '../../api/generated/schema';

export type Goal = components['schemas']['Goal'];

/** Plain-language goal copy; describes the routes picked, never the person. Shared by the goals step and the session builder. */
export const GOALS: readonly { id: Goal; label: string; hint: string }[] = [
  {
    id: 'TECHNIQUE',
    label: 'Technique',
    hint: 'Movement quality on routes within reach.',
  },
  { id: 'STRENGTH', label: 'Strength', hint: 'Short routes with hard moves.' },
  {
    id: 'ENDURANCE',
    label: 'Endurance',
    hint: 'Longer routes with more moves.',
  },
  {
    id: 'PROJECT',
    label: 'Project',
    hint: 'One route at your limit to come back to.',
  },
];

export const goalLabel = (goal: string): string =>
  GOALS.find((g) => g.id === goal)?.label ?? goal;
