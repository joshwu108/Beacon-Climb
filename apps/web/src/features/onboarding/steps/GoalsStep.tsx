import { useId, useState, type FormEvent } from 'react';
import { ApiErrorBanner } from '../../../components/ui/ApiErrorBanner';
import { FieldError, describedBy } from '../../../components/ui/FieldError';
import {
  fieldErrorsFrom,
  type FieldErrorMap,
} from '../../../components/ui/field-errors';
import type { components } from '../../../api/generated/schema';
import { StepHeading } from '../StepHeading';
import type { PreferencesDraft } from '../steps';
import { useSavePreferences } from '../useOnboardingMutations';

import { GOALS, type Goal } from '../goals';

export type GradeBand = components['schemas']['GradeBand'];
export { GOALS, type Goal };

export interface GoalsStepProps {
  readonly draft: PreferencesDraft;
  readonly onSaved: () => void;
  /** Called with board_id / angle_deg field errors so the page can return to the board step. */
  readonly onBoardFieldErrors: (errors: FieldErrorMap) => void;
  readonly onBack: () => void;
}

/* Plain-language descriptions of where to start. They describe the routes shown first,
   never the person; the band is a starting point the ranking policy maps to grades. */
export const GRADE_BANDS: readonly {
  id: GradeBand;
  label: string;
  hint: string;
}[] = [
  {
    id: 'NOVICE',
    label: 'Novice',
    hint: 'New to board climbing. Start with the easiest routes on the board.',
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
const BOARD_FIELDS = ['board_id', 'angle_deg'] as const;
const STEP_FIELDS = ['initial_grade_band', 'goals'] as const;

const toggle = (list: readonly Goal[], goal: Goal): Goal[] =>
  list.includes(goal) ? list.filter((g) => g !== goal) : [...list, goal];

/**
 * Step 4: starting grade band and goals, then PUT /v1/me/preferences with the board
 * and angle from step 3. Field errors for board_id / angle_deg are handed back so the
 * person is returned to the board step with the message in place.
 */
export const GoalsStep = ({
  draft,
  onSaved,
  onBoardFieldErrors,
  onBack,
}: GoalsStepProps) => {
  const ids = useId();
  const save = useSavePreferences();
  const [band, setBand] = useState<GradeBand | null>(null);
  const [goals, setGoals] = useState<readonly Goal[]>([]);
  const [touched, setTouched] = useState(false);

  const serverErrors = fieldErrorsFrom(save.error, STEP_FIELDS);
  const bandError =
    serverErrors.initial_grade_band ??
    (touched && band === null ? 'Choose a starting band.' : null);
  const goalsError =
    serverErrors.goals ??
    (goals.length > MAX_GOALS ? `Choose up to ${MAX_GOALS} goals.` : null);
  const showBanner = save.isError && Object.keys(serverErrors).length === 0;

  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    setTouched(true);
    if (band === null || goals.length > MAX_GOALS) return;
    save.mutate(
      {
        board_id: draft.boardId,
        angle_deg: draft.angleDeg,
        initial_grade_band: band,
        goals: [...goals],
      },
      {
        onSuccess: onSaved,
        onError: (error) => {
          const boardErrors = fieldErrorsFrom(error, BOARD_FIELDS);
          if (Object.keys(boardErrors).length > 0)
            onBoardFieldErrors(boardErrors);
        },
      },
    );
  };

  const bandErrorId = `${ids}-band-error`;
  const goalsErrorId = `${ids}-goals-error`;

  return (
    <form
      className="sheet onboarding__form"
      onSubmit={submit}
      aria-labelledby="step-heading"
      aria-busy={save.isPending}
      noValidate
    >
      <StepHeading step="goals" />
      <p className="field__hint">
        Until you log attempts, session picks are based on this starting band,
        your goals and each route&apos;s grade. Change either at any time.
      </p>

      {showBanner ? <ApiErrorBanner error={save.error} /> : null}

      <fieldset
        className="choice-group"
        aria-describedby={describedBy(bandError !== null && bandErrorId)}
        aria-invalid={bandError !== null}
      >
        <legend className="field__label">
          Where should the first picks start?
        </legend>
        {GRADE_BANDS.map((option) => (
          <label
            className="choice"
            key={option.id}
            htmlFor={`${ids}-band-${option.id}`}
          >
            <input
              id={`${ids}-band-${option.id}`}
              type="radio"
              name="initial_grade_band"
              value={option.id}
              checked={band === option.id}
              onChange={() => setBand(option.id)}
              aria-invalid={bandError !== null}
            />
            <span className="choice__body">
              <span className="choice__title">{option.label}</span>
              <span className="choice__hint">{option.hint}</span>
            </span>
          </label>
        ))}
        <FieldError id={bandErrorId} message={bandError} />
      </fieldset>

      <fieldset
        className="choice-group"
        aria-describedby={describedBy(
          `${ids}-goals-hint`,
          goalsError !== null && goalsErrorId,
        )}
        aria-invalid={goalsError !== null}
      >
        <legend className="field__label">
          What do you want from sessions?
        </legend>
        <p className="field__hint" id={`${ids}-goals-hint`}>
          Pick up to {MAX_GOALS}. You can leave this empty.
        </p>
        {GOALS.map((option) => (
          <label
            className="choice"
            key={option.id}
            htmlFor={`${ids}-goal-${option.id}`}
          >
            <input
              id={`${ids}-goal-${option.id}`}
              type="checkbox"
              name="goals"
              value={option.id}
              checked={goals.includes(option.id)}
              onChange={() => setGoals(toggle(goals, option.id))}
              aria-invalid={goalsError !== null}
            />
            <span className="choice__body">
              <span className="choice__title">{option.label}</span>
              <span className="choice__hint">{option.hint}</span>
            </span>
          </label>
        ))}
        <FieldError id={goalsErrorId} message={goalsError} />
      </fieldset>

      <div className="onboarding__actions onboarding__actions--row">
        <button
          type="button"
          className="button button--secondary"
          onClick={onBack}
        >
          Back
        </button>
        <button type="submit" className="button" disabled={save.isPending}>
          {save.isPending ? 'Saving' : 'Save and start exploring'}
        </button>
      </div>
    </form>
  );
};
