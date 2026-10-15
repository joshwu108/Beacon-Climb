import { useId, type FormEvent } from 'react';
import { FieldError, describedBy } from '../../components/ui/FieldError';
import type { FieldErrorMap } from '../../components/ui/field-errors';
import {
  EFFORT_MAX,
  EFFORT_MIN,
  NOTES_MAX,
  type AttemptFormValues,
} from './attempt-form';

export interface AttemptFormProps {
  readonly values: AttemptFormValues;
  readonly errors: FieldErrorMap;
  readonly isBusy: boolean;
  readonly isSubmitDisabled?: boolean;
  readonly submitLabel: string;
  readonly busyLabel: string;
  /** Hidden when correcting: occurred_at is not in AttemptPatch. */
  readonly showOccurredAt: boolean;
  readonly label: string;
  readonly onChange: (values: AttemptFormValues) => void;
  readonly onSubmit: () => void;
  readonly onCancel?: () => void;
  readonly children?: React.ReactNode;
}

const EFFORT_WORDS: Readonly<Record<number, string>> = {
  1: 'very light',
  5: 'moderate',
  10: 'maximum effort',
};

/**
 * The attempt fields (create and correct). Submit is guarded by the caller: the form
 * only reports intent; it never sends anything itself.
 */
export const AttemptForm = ({
  values,
  errors,
  isBusy,
  isSubmitDisabled = false,
  submitLabel,
  busyLabel,
  showOccurredAt,
  label,
  onChange,
  onSubmit,
  onCancel,
  children,
}: AttemptFormProps) => {
  const ids = useId();
  const set = (patch: Partial<AttemptFormValues>): void =>
    onChange({ ...values, ...patch });
  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (!isBusy && !isSubmitDisabled) onSubmit();
  };
  const errorId = (field: string): string => `${ids}-${field}-error`;
  const remaining = NOTES_MAX - values.notes.length;

  return (
    <form
      className="attempt-form"
      aria-label={label}
      onSubmit={submit}
      aria-busy={isBusy}
      noValidate
    >
      {children}
      <fieldset className="attempt-form__fields" disabled={isBusy}>
        <legend className="visually-hidden">Details</legend>

        {showOccurredAt ? (
          <div className="field">
            <label className="field__label" htmlFor={`${ids}-when`}>
              When
            </label>
            <input
              className="field__input"
              id={`${ids}-when`}
              name="occurred_at"
              type="datetime-local"
              value={values.occurredAt}
              onChange={(e) => set({ occurredAt: e.target.value })}
              aria-invalid={Boolean(errors.occurred_at)}
              aria-describedby={describedBy(
                `${ids}-when-hint`,
                errors.occurred_at && errorId('occurred_at'),
              )}
            />
            <p className="field__hint" id={`${ids}-when-hint`}>
              Local time. Up to five minutes ahead is accepted.
            </p>
            <FieldError
              id={errorId('occurred_at')}
              message={errors.occurred_at}
            />
          </div>
        ) : null}

        <div className="field">
          <label className="field__label" htmlFor={`${ids}-count`}>
            Attempts
          </label>
          <div className="stepper">
            <button
              type="button"
              className="button button--secondary stepper__button"
              aria-label="One attempt fewer"
              onClick={() =>
                set({ attemptsCount: Math.max(1, values.attemptsCount - 1) })
              }
              disabled={values.attemptsCount <= 1}
            >
              −
            </button>
            <input
              className="field__input stepper__input"
              id={`${ids}-count`}
              name="attempts_count"
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              value={values.attemptsCount}
              onChange={(e) => set({ attemptsCount: Number(e.target.value) })}
              aria-invalid={Boolean(errors.attempts_count)}
              aria-describedby={describedBy(
                errors.attempts_count && errorId('attempts_count'),
              )}
            />
            <button
              type="button"
              className="button button--secondary stepper__button"
              aria-label="One attempt more"
              onClick={() => set({ attemptsCount: values.attemptsCount + 1 })}
            >
              +
            </button>
          </div>
          <FieldError
            id={errorId('attempts_count')}
            message={errors.attempts_count}
          />
        </div>

        <div className="field">
          <label className="switch" htmlFor={`${ids}-sent`}>
            <input
              id={`${ids}-sent`}
              name="sent"
              type="checkbox"
              role="switch"
              checked={values.sent}
              onChange={(e) => set({ sent: e.target.checked })}
            />
            <span className="switch__track" aria-hidden="true" />
            <span className="switch__body">
              <span className="field__label">Sent</span>
              <span className="field__hint">
                {values.sent
                  ? 'You topped out on one of these attempts.'
                  : 'Not sent. Progress below is optional.'}
              </span>
            </span>
          </label>
        </div>

        {!values.sent ? (
          <div className="field">
            <label className="field__label" htmlFor={`${ids}-progress`}>
              How far did you get?{' '}
              <span className="attempt-form__value">
                {values.completionPercent === null
                  ? 'not recorded'
                  : `${values.completionPercent}%`}
              </span>
            </label>
            <input
              className="range"
              id={`${ids}-progress`}
              name="completion_fraction"
              type="range"
              min={0}
              max={100}
              step={5}
              value={values.completionPercent ?? 0}
              onChange={(e) =>
                set({ completionPercent: Number(e.target.value) })
              }
              aria-valuetext={
                values.completionPercent === null
                  ? 'not recorded'
                  : `${values.completionPercent} percent`
              }
              aria-invalid={Boolean(errors.completion_fraction)}
              aria-describedby={describedBy(
                `${ids}-progress-hint`,
                errors.completion_fraction && errorId('completion_fraction'),
              )}
            />
            <p className="field__hint" id={`${ids}-progress-hint`}>
              Optional. Recorded as progress only; nothing is inferred from it.
              {values.completionPercent !== null ? (
                <>
                  {' '}
                  <button
                    type="button"
                    className="link-button"
                    onClick={() => set({ completionPercent: null })}
                  >
                    Clear
                  </button>
                </>
              ) : null}
            </p>
            <FieldError
              id={errorId('completion_fraction')}
              message={errors.completion_fraction}
            />
          </div>
        ) : null}

        <fieldset
          className="field attempt-form__effort"
          aria-describedby={describedBy(
            `${ids}-effort-hint`,
            errors.perceived_effort && errorId('perceived_effort'),
          )}
          aria-invalid={Boolean(errors.perceived_effort)}
        >
          <legend className="field__label">Perceived effort (optional)</legend>
          <div className="segmented segmented--scale" role="presentation">
            {Array.from({ length: EFFORT_MAX - EFFORT_MIN + 1 }, (_, i) => {
              const n = EFFORT_MIN + i;
              const word = EFFORT_WORDS[n];
              return (
                <label
                  className="segmented__option"
                  key={n}
                  htmlFor={`${ids}-effort-${n}`}
                >
                  <input
                    id={`${ids}-effort-${n}`}
                    type="radio"
                    name="perceived_effort"
                    value={n}
                    checked={values.perceivedEffort === n}
                    onChange={() => set({ perceivedEffort: n })}
                    aria-label={word ? `${n}, ${word}` : String(n)}
                  />
                  <span aria-hidden="true">{n}</span>
                </label>
              );
            })}
          </div>
          <p className="field__hint" id={`${ids}-effort-hint`}>
            1 very light, 10 maximum effort.
            {values.perceivedEffort !== null ? (
              <>
                {' '}
                <button
                  type="button"
                  className="link-button"
                  onClick={() => set({ perceivedEffort: null })}
                >
                  Clear
                </button>
              </>
            ) : null}
          </p>
          <FieldError
            id={errorId('perceived_effort')}
            message={errors.perceived_effort}
          />
        </fieldset>

        <div className="field">
          <label className="field__label" htmlFor={`${ids}-notes`}>
            Notes (optional)
          </label>
          <textarea
            className="field__input attempt-form__notes"
            id={`${ids}-notes`}
            name="notes"
            rows={3}
            maxLength={NOTES_MAX}
            value={values.notes}
            onChange={(e) => set({ notes: e.target.value })}
            aria-invalid={Boolean(errors.notes)}
            aria-describedby={describedBy(
              `${ids}-notes-count`,
              errors.notes && errorId('notes'),
            )}
          />
          <p className="field__hint" id={`${ids}-notes-count`}>
            {values.notes.length}/{NOTES_MAX} characters
            {remaining <= 50 ? `, ${remaining} left` : ''}. Only you can read
            notes.
          </p>
          <FieldError id={errorId('notes')} message={errors.notes} />
        </div>
      </fieldset>

      <div className="attempt-form__actions">
        <button
          type="submit"
          className="button"
          disabled={isBusy || isSubmitDisabled}
        >
          {isBusy ? busyLabel : submitLabel}
        </button>
        {onCancel ? (
          <button
            type="button"
            className="button button--secondary"
            onClick={onCancel}
            disabled={isBusy}
          >
            Cancel
          </button>
        ) : null}
      </div>
    </form>
  );
};
