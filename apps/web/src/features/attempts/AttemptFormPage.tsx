import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { isApiError } from '../../api/errors';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import {
  hasFieldErrors,
  type FieldErrorMap,
} from '../../components/ui/field-errors';
import { tokenStore } from '../auth/token-store';
import { useCurrentUserId } from '../auth/useCurrentUserId';
import { hasActiveConsent, useMe } from '../auth/useMe';
import { formatGrade } from '../explore/grade';
import { useRoute } from '../explore/useRoutes';
import { AttemptForm } from './AttemptForm';
import {
  ATTEMPT_FIELDS,
  defaultValues,
  toAttemptCreate,
  validateAttempt,
  type AttemptFormValues,
} from './attempt-form';
import {
  isRetryable,
  submitDraft,
  useDrafts,
  useReconnectSync,
} from './attempt-sync';
import { draftStore, type DraftError } from './draft-store';
import { attemptsQueryKey } from './useAttempts';
import './attempts.css';

const newIds = () => ({
  draftId: crypto.randomUUID(),
  idempotencyKey: crypto.randomUUID(),
});

const serverFieldErrors = (error: DraftError | null): FieldErrorMap =>
  error === null
    ? {}
    : Object.fromEntries(
        error.field_errors
          .filter((fe) =>
            (ATTEMPT_FIELDS as readonly string[]).includes(fe.field),
          )
          .map((fe) => [
            fe.field,
            fe.code === 'OCCURRED_AT_IN_FUTURE'
              ? 'That time is in the future. Pick a time that has already happened.'
              : fe.message || 'Check this answer and try again.',
          ]),
      );

const Header = () => (
  <>
    <p className="detail__back">
      <Link to="/attempts">← Back to the log</Link>
    </p>
    <p className="page__eyebrow">Log</p>
    <h1>Log an attempt</h1>
  </>
);

/**
 * /attempts/new?route_variant_id=... (BC-0118). One draft id and one Idempotency-Key are
 * generated when the page opens; the draft is persisted on the first Save and every
 * retry reuses it, so a double click, a lost reply or a reconnect can never create two
 * attempts. A 409 IDEMPOTENCY_CONFLICT is the only path to a new key, and it is explicit.
 * The draft is stamped with the signed-in user's id, so Save waits until GET /v1/me has
 * identified the user for this tab (BC-0123 F4).
 */
export const AttemptFormPage = () => {
  const hasToken = tokenStore.get() !== null;
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const routeVariantId = searchParams.get('route_variant_id') ?? '';
  const me = useMe({ enabled: hasToken });
  const ownerUserId = useCurrentUserId();
  const route = useRoute(routeVariantId);
  const [ids, setIds] = useState(newIds);
  const [values, setValues] = useState<AttemptFormValues>(defaultValues);
  const [clientErrors, setClientErrors] = useState<FieldErrorMap>({});
  const [isSubmitting, setSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const drafts = useDrafts();
  const draft = drafts.find((d) => d.draft_id === ids.draftId) ?? null;
  useReconnectSync();

  // Saved (by this page or by the reconnect sync): the history shows the new row.
  const savedAttemptId =
    draft?.state === 'synced' ? (draft.attempt?.attempt_id ?? null) : null;
  const isSaved = draft?.state === 'synced';
  useEffect(() => {
    if (!isSaved) return;
    submittingRef.current = true;
    void queryClient
      .invalidateQueries({ queryKey: attemptsQueryKey })
      .then(() => navigate('/attempts', { state: { saved: savedAttemptId } }));
  }, [isSaved, navigate, queryClient, savedAttemptId]);

  if (!hasToken) return <Navigate to="/onboarding" replace />;

  if (routeVariantId === '') {
    return (
      <article className="page attempts">
        <Header />
        <p className="page__lede">
          Pick the route first: open one in <Link to="/explore">Explore</Link>{' '}
          or press &ldquo;Log attempt&rdquo; on a session card.
        </p>
      </article>
    );
  }

  const consentOff = me.isSuccess && !hasActiveConsent(me.data);
  // No owner yet: the profile request failed before it ever answered in this tab.
  const ownerUnknownError =
    ownerUserId === null && me.isError ? me.error : null;
  const failure = draft?.state === 'failed' ? draft.error : null;
  const errors: FieldErrorMap = {
    ...serverFieldErrors(failure),
    ...clientErrors,
  };

  const save = async (): Promise<void> => {
    const problems = validateAttempt(values);
    setClientErrors(problems);
    if (hasFieldErrors(problems)) return;
    // Synchronous guard: a second click before React re-renders is ignored.
    if (submittingRef.current) return;
    // The button is disabled until the owner is known; this guards the keyboard path.
    if (ownerUserId === null) return;
    submittingRef.current = true;
    setSubmitting(true);
    let isDone = false;
    try {
      await draftStore.ready();
      const existing = draftStore.get(ids.draftId);
      if (existing?.state === 'synced') {
        isDone = true;
        return;
      }
      await draftStore.put({
        draft_id: ids.draftId,
        idempotency_key: ids.idempotencyKey,
        owner_user_id: ownerUserId,
        body: toAttemptCreate(routeVariantId, values),
        state: 'draft',
        attempt: null,
        error: null,
        created_at: existing?.created_at ?? new Date().toISOString(),
        route_name: route.data?.name ?? null,
      });
      const result = await submitDraft(ids.draftId);
      isDone = result.state === 'synced';
    } finally {
      // A saved draft keeps the page locked: nothing on it may send again.
      if (!isDone) {
        submittingRef.current = false;
        setSubmitting(false);
      }
    }
  };

  const startNewDraft = (): void => setIds(newIds());

  const grade = route.data ? formatGrade(route.data) : null;

  return (
    <article className="page attempts">
      <Header />
      <div className="attempts__columns">
        <section className="sheet attempts__route" aria-label="Route">
          <p className="page__eyebrow">Route</p>
          {route.isPending ? (
            <p role="status" className="field__hint">
              Loading route
            </p>
          ) : route.isError ? (
            <p className="field__hint">
              {isApiError(route.error) && route.error.status === 404
                ? `Route ${routeVariantId} isn't available; the attempt can still be logged against its id.`
                : "The route details couldn't be loaded; the id below is still used."}
            </p>
          ) : (
            <>
              <h2 className="attempts__route-name">
                <Link to={`/explore/${encodeURIComponent(routeVariantId)}`}>
                  {route.data.name}
                </Link>
              </h2>
              <p className="field__hint">
                {grade?.isUngraded ? 'ungraded' : `grade ${grade?.primary}`}
                {grade?.label ? ` (${grade.label})` : ''} ·{' '}
                {route.data.angle_deg}°
              </p>
            </>
          )}
          <p className="attempts__route-id">
            <code>{routeVariantId}</code>
          </p>
        </section>

        <div className="sheet attempts__form-sheet">
          {consentOff ? (
            <div className="banner banner--warning" role="status">
              <p className="banner__title">Logging is off.</p>
              <p>
                Attempts are only kept with your consent.{' '}
                <Link to="/onboarding/consent">Turn on logging</Link> to save
                this one.
              </p>
            </div>
          ) : null}

          {ownerUnknownError !== null ? (
            <ApiErrorBanner
              error={ownerUnknownError}
              title="Your account couldn't be checked, so nothing can be saved yet."
              onRetry={() => void me.refetch()}
            />
          ) : null}

          {failure !== null ? (
            <FailurePanel
              error={failure}
              onRetry={() => void save()}
              onNewDraft={startNewDraft}
              isBusy={isSubmitting}
            />
          ) : null}

          <AttemptForm
            label="Log an attempt"
            values={values}
            errors={errors}
            isBusy={isSubmitting}
            isSubmitDisabled={consentOff || ownerUserId === null}
            submitLabel={failure !== null ? 'Try again' : 'Save attempt'}
            busyLabel="Saving"
            showOccurredAt
            onChange={(next) => {
              setValues(next);
              if (hasFieldErrors(clientErrors)) setClientErrors({});
            }}
            onSubmit={() => void save()}
          />
          <p className="field__hint">
            Saved with a key made once for this draft, so trying again never
            creates a duplicate.
          </p>
        </div>
      </div>
    </article>
  );
};

const FailurePanel = ({
  error,
  onRetry,
  onNewDraft,
  isBusy,
}: {
  error: DraftError;
  onRetry: () => void;
  onNewDraft: () => void;
  isBusy: boolean;
}) => {
  if (error.status === 403) {
    return (
      <div className="banner banner--warning" role="alert">
        <p className="banner__title">
          Logging is off, so this attempt wasn&apos;t saved.
        </p>
        <p>
          <Link to="/onboarding/consent">Turn on logging</Link>, then try again.
          The draft stays on this device.
        </p>
      </div>
    );
  }
  if (error.status === 409) {
    return (
      <div className="banner banner--error" role="alert">
        <p className="banner__title">
          This submission was already saved with different details.
        </p>
        <p>
          The same draft key reached the server with another body, so it was not
          applied. <Link to="/attempts">View the saved attempt</Link>, or save
          these details as a separate attempt with a new key.
        </p>
        {error.request_id ? (
          <p className="banner__meta">Request id {error.request_id}</p>
        ) : null}
        <button
          type="button"
          className="button button--secondary banner__action"
          onClick={onNewDraft}
        >
          Create new draft
        </button>
      </div>
    );
  }
  if (error.status === 422) {
    return (
      <div className="banner banner--error" role="alert">
        <p className="banner__title">We couldn&apos;t save this attempt.</p>
        <p>
          {error.field_errors.length > 0
            ? 'Check the highlighted answers and try again.'
            : error.message}
        </p>
        {error.request_id ? (
          <p className="banner__meta">Request id {error.request_id}</p>
        ) : null}
      </div>
    );
  }
  const unknownOutcome = error.status === 0;
  return (
    <div className="banner banner--warning" role="alert">
      <p className="banner__title">
        {unknownOutcome
          ? "We didn't get a reply, so we can't tell if it saved."
          : "We couldn't save this attempt. Nothing was recorded."}
      </p>
      <p>
        {unknownOutcome
          ? "It's kept on this device and will be sent again when you're back online. Trying again is safe and won't create a duplicate."
          : 'Trying again reuses the same key, so it cannot create a duplicate.'}
      </p>
      {error.request_id ? (
        <p className="banner__meta">Request id {error.request_id}</p>
      ) : null}
      {isRetryable(error) ? (
        <div className="attempt-form__actions">
          <button
            type="button"
            className="button button--secondary banner__action"
            onClick={onRetry}
            disabled={isBusy}
          >
            Retry
          </button>
          <Link className="banner__action" to="/attempts">
            Keep as draft
          </Link>
        </div>
      ) : null}
    </div>
  );
};
