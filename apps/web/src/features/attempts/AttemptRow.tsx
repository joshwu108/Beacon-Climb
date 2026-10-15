import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { isApiError } from '../../api/errors';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import {
  fieldErrorsFrom,
  hasFieldErrors,
} from '../../components/ui/field-errors';
import { formatDateTime } from '../../lib/format';
import { formatGrade } from '../explore/grade';
import { AttemptForm } from './AttemptForm';
import {
  ATTEMPT_FIELDS,
  toAttemptPatch,
  validateAttempt,
  valuesFromAttempt,
  type AttemptFormValues,
} from './attempt-form';
import {
  fetchAttempt,
  replaceCachedAttempt,
  usePatchAttempt,
  type Attempt,
} from './useAttempts';

export interface AttemptRowProps {
  readonly attempt: Attempt;
  readonly isJustSaved: boolean;
}

type Mode = 'view' | 'correct' | 'retract';

/**
 * One history row: facts as text, "Correct" (inline form, PATCH with If-Match) and
 * "Undo (retract)" (confirm, then PATCH {retract: true}). A 409 REVISION_CONFLICT shows
 * the server's current revision and a Refresh that re-reads the attempt before retrying.
 */
export const AttemptRow = ({ attempt, isJustSaved }: AttemptRowProps) => {
  const queryClient = useQueryClient();
  const patch = usePatchAttempt();
  const [mode, setMode] = useState<Mode>('view');
  const [values, setValues] = useState<AttemptFormValues | null>(null);
  const [clientErrors, setClientErrors] = useState({});
  const [isRefreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<unknown>(null);

  const grade = formatGrade(attempt.route);
  const isRetracted = attempt.status === 'RETRACTED';
  const headingId = `attempt-${attempt.attempt_id}`;
  const conflict =
    isApiError(patch.error) && patch.error.code === 'REVISION_CONFLICT'
      ? patch.error
      : null;

  const close = (): void => {
    setMode('view');
    setValues(null);
    setClientErrors({});
    patch.reset();
  };

  const startCorrect = (): void => {
    setValues(valuesFromAttempt(attempt));
    setMode('correct');
  };

  const saveCorrection = (): void => {
    if (values === null) return;
    const problems = validateAttempt(values, { requireTime: false });
    setClientErrors(problems);
    if (hasFieldErrors(problems)) return;
    const body = toAttemptPatch(attempt, values);
    if (Object.keys(body).length === 0) {
      close();
      return;
    }
    patch.mutate(
      {
        attemptId: attempt.attempt_id,
        revision: attempt.revision,
        patch: body,
      },
      { onSuccess: close },
    );
  };

  const retract = (): void =>
    patch.mutate(
      {
        attemptId: attempt.attempt_id,
        revision: attempt.revision,
        patch: { retract: true },
      },
      { onSuccess: close },
    );

  const refresh = async (): Promise<void> => {
    setRefreshing(true);
    setRefreshError(null);
    try {
      const latest = await fetchAttempt(attempt.attempt_id);
      replaceCachedAttempt(queryClient, latest);
      if (mode === 'correct') setValues(valuesFromAttempt(latest));
      patch.reset();
    } catch (error) {
      setRefreshError(error);
    } finally {
      setRefreshing(false);
    }
  };

  const serverErrors = fieldErrorsFrom(patch.error, ATTEMPT_FIELDS);

  return (
    <li
      className={`attempt${isRetracted ? ' attempt--retracted' : ''}`}
      aria-labelledby={headingId}
    >
      <div className="attempt__head">
        <h3 className="attempt__name" id={headingId}>
          <Link to={`/explore/${encodeURIComponent(attempt.route_variant_id)}`}>
            {attempt.route.name}
          </Link>
        </h3>
        <span className="attempt__grade">
          {grade.isUngraded ? 'ungraded' : grade.primary}
          {grade.label ? ` · ${grade.label}` : ''}
        </span>
      </div>
      <p className="attempt__facts">
        <time dateTime={attempt.occurred_at}>
          {formatDateTime(attempt.occurred_at)}
        </time>
        <span>
          {attempt.attempts_count} attempt
          {attempt.attempts_count === 1 ? '' : 's'}
        </span>
        <span className="attempt__sent">
          {attempt.sent ? 'Sent' : 'Not sent'}
        </span>
        {attempt.completion_fraction !== null ? (
          <span>Progress {Math.round(attempt.completion_fraction * 100)}%</span>
        ) : null}
        {attempt.perceived_effort !== null ? (
          <span>Effort {attempt.perceived_effort}/10</span>
        ) : null}
      </p>
      {attempt.notes ? <p className="attempt__notes">{attempt.notes}</p> : null}
      <p className="attempt__meta">
        <span>Revision {attempt.revision}</span>
        {attempt.revision > 1 && !isRetracted ? (
          <span className="stamp">Corrected</span>
        ) : null}
        {isRetracted ? (
          <span className="stamp stamp--warning">Retracted</span>
        ) : null}
        {isJustSaved ? (
          <span className="attempt__saved" role="status">
            Saved as revision {attempt.revision}.
          </span>
        ) : null}
      </p>

      {conflict !== null ? (
        <div className="banner banner--warning" role="alert">
          <p className="banner__title">
            This attempt changed elsewhere (revision {conflict.currentRevision}
            ). Refresh
          </p>
          <p>
            Refresh to see the latest saved version, then apply your change
            again.
          </p>
          <button
            type="button"
            className="button button--secondary banner__action"
            onClick={() => void refresh()}
            disabled={isRefreshing}
          >
            {isRefreshing ? 'Refreshing' : 'Refresh'}
          </button>
        </div>
      ) : patch.isError && !hasFieldErrors(serverErrors) ? (
        <ApiErrorBanner
          error={patch.error}
          title="This change couldn't be saved."
          onRetry={mode === 'retract' ? retract : saveCorrection}
        />
      ) : null}
      {refreshError !== null ? (
        <ApiErrorBanner
          error={refreshError}
          title="The latest version couldn't be loaded."
          onRetry={() => void refresh()}
        />
      ) : null}

      {mode === 'correct' && values !== null ? (
        <div className="attempt__edit">
          <AttemptForm
            label={`Correct ${attempt.route.name}`}
            values={values}
            errors={{ ...serverErrors, ...clientErrors }}
            isBusy={patch.isPending}
            submitLabel="Save correction"
            busyLabel="Saving"
            showOccurredAt={false}
            onChange={setValues}
            onSubmit={saveCorrection}
            onCancel={close}
          >
            <p className="field__hint">
              To change the route or time, retract this attempt and log a new
              one.
            </p>
          </AttemptForm>
        </div>
      ) : mode === 'retract' ? (
        <div
          className="attempt__confirm"
          role="group"
          aria-label="Confirm retract"
        >
          <p>
            Retract this attempt? It stays in your history marked Retracted and
            is not used for your picks.
          </p>
          <div className="attempt__actions">
            <button
              type="button"
              className="button"
              onClick={retract}
              disabled={patch.isPending}
            >
              {patch.isPending ? 'Retracting' : 'Retract'}
            </button>
            <button
              type="button"
              className="button button--secondary"
              onClick={close}
              disabled={patch.isPending}
            >
              Keep attempt
            </button>
          </div>
        </div>
      ) : isRetracted ? null : (
        <div className="attempt__actions">
          <button
            type="button"
            className="button button--secondary"
            onClick={startCorrect}
          >
            Correct
          </button>
          <button
            type="button"
            className="button button--secondary"
            onClick={() => setMode('retract')}
          >
            Undo (retract)
          </button>
        </div>
      )}
    </li>
  );
};
