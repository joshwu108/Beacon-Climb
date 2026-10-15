import { useId, type FormEvent } from 'react';
import { FieldError, describedBy } from '../../components/ui/FieldError';
import type { FieldErrorMap } from '../../components/ui/field-errors';
import { GOALS } from '../onboarding/goals';
import {
  MAX_ROUTES,
  MIN_ROUTES,
  clampRoutes,
  type Goal,
} from './session-params';

export interface SessionFormProps {
  readonly goal: Goal;
  readonly routes: number;
  readonly isBusy: boolean;
  readonly hasLineup: boolean;
  readonly errors: FieldErrorMap;
  readonly onChange: (next: { goal?: Goal; routes?: number }) => void;
  readonly onSubmit: () => void;
}

/** Goal radios and a 3..6 route stepper; inert while a request is in flight. */
export const SessionForm = ({
  goal,
  routes,
  isBusy,
  hasLineup,
  errors,
  onChange,
  onSubmit,
}: SessionFormProps) => {
  const ids = useId();
  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (!isBusy) onSubmit();
  };
  const goalErrorId = `${ids}-goal-error`;
  const routesErrorId = `${ids}-routes-error`;
  return (
    <form
      className="sheet session__form"
      aria-label="Session setup"
      onSubmit={submit}
      aria-busy={isBusy}
      noValidate
    >
      <fieldset
        className="choice-group"
        disabled={isBusy}
        aria-describedby={describedBy(errors.goal && goalErrorId)}
        aria-invalid={Boolean(errors.goal)}
      >
        <legend className="field__label">What is this session for?</legend>
        {GOALS.map((option) => (
          <label
            className="choice"
            key={option.id}
            htmlFor={`${ids}-goal-${option.id}`}
          >
            <input
              id={`${ids}-goal-${option.id}`}
              type="radio"
              name="goal"
              value={option.id}
              checked={goal === option.id}
              onChange={() => onChange({ goal: option.id })}
            />
            <span className="choice__body">
              <span className="choice__title">{option.label}</span>
              <span className="choice__hint">{option.hint}</span>
            </span>
          </label>
        ))}
        <FieldError id={goalErrorId} message={errors.goal} />
      </fieldset>

      <div className="field">
        <label className="field__label" htmlFor={`${ids}-routes`}>
          Routes in the session
        </label>
        <div className="stepper">
          <button
            type="button"
            className="button button--secondary stepper__button"
            onClick={() => onChange({ routes: clampRoutes(routes - 1) })}
            disabled={isBusy || routes <= MIN_ROUTES}
            aria-label="Fewer routes"
          >
            −
          </button>
          <input
            className="field__input stepper__input"
            id={`${ids}-routes`}
            name="routes"
            type="number"
            inputMode="numeric"
            min={MIN_ROUTES}
            max={MAX_ROUTES}
            step={1}
            value={routes}
            disabled={isBusy}
            onChange={(e) =>
              onChange({ routes: clampRoutes(Number(e.target.value)) })
            }
            aria-describedby={describedBy(
              `${ids}-routes-hint`,
              errors.max_routes && routesErrorId,
            )}
            aria-invalid={Boolean(errors.max_routes)}
          />
          <button
            type="button"
            className="button button--secondary stepper__button"
            onClick={() => onChange({ routes: clampRoutes(routes + 1) })}
            disabled={isBusy || routes >= MAX_ROUTES}
            aria-label="More routes"
          >
            +
          </button>
        </div>
        <p className="field__hint" id={`${ids}-routes-hint`}>
          {MIN_ROUTES} to {MAX_ROUTES} routes.
        </p>
        <FieldError id={routesErrorId} message={errors.max_routes} />
      </div>

      <div className="session__actions">
        <button type="submit" className="button" disabled={isBusy}>
          {isBusy
            ? 'Building'
            : hasLineup
              ? 'Refresh picks'
              : 'Build my session'}
        </button>
      </div>
    </form>
  );
};
