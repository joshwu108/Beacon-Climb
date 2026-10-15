import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, Navigate, useLocation } from 'react-router';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { formatDateTime } from '../../lib/format';
import { tokenStore } from '../auth/token-store';
import { useMe } from '../auth/useMe';
import { AttemptRow } from './AttemptRow';
import {
  isRetryable,
  submitDraft,
  useDrafts,
  useReconnectSync,
} from './attempt-sync';
import { draftStore, type AttemptDraft } from './draft-store';
import { attemptsQueryKey, useAttempts } from './useAttempts';
import './attempts.css';

const SKELETON_ROWS = 4;

const draftStamp = (draft: AttemptDraft): string => {
  switch (draft.state) {
    case 'sending':
      return 'Saving';
    case 'synced':
      return 'Saved';
    case 'failed':
      return isRetryable(draft.error) ? 'Waiting to save' : 'Needs attention';
    default:
      return 'Draft on this device';
  }
};

const draftProblem = (draft: AttemptDraft): string | null => {
  const error = draft.error;
  if (draft.state !== 'failed' || error === null) return null;
  if (error.status === 0)
    return "No reply yet; it will be sent again when you're online.";
  if (error.status === 403)
    return 'Logging is off. Turn on logging, then retry.';
  if (error.status === 409)
    return 'Already saved with different details. Discard this draft or log a new attempt.';
  if (error.status === 422)
    return 'The server rejected these details. Log a new attempt.';
  return `Could not be saved (${error.message}).`;
};

const DraftItem = ({
  draft,
  onRetry,
  onDiscard,
}: {
  draft: AttemptDraft;
  onRetry: () => void;
  onDiscard: () => void;
}) => {
  const problem = draftProblem(draft);
  const canRetry = draft.state === 'failed' && isRetryable(draft.error);
  return (
    <li className="attempt attempt--draft">
      <div className="attempt__head">
        <h3 className="attempt__name">
          {draft.route_name ?? draft.body.route_variant_id}
        </h3>
        <span className="stamp stamp--warning">{draftStamp(draft)}</span>
      </div>
      <p className="attempt__facts">
        <time dateTime={draft.body.occurred_at}>
          {formatDateTime(draft.body.occurred_at)}
        </time>
        <span>
          {draft.body.attempts_count} attempt
          {draft.body.attempts_count === 1 ? '' : 's'}
        </span>
        <span>{draft.body.sent ? 'Sent' : 'Not sent'}</span>
      </p>
      {problem ? <p className="field__hint">{problem}</p> : null}
      <div className="attempt__actions">
        {canRetry ? (
          <button
            type="button"
            className="button button--secondary"
            onClick={onRetry}
          >
            Retry
          </button>
        ) : null}
        {draft.error?.status === 409 || draft.error?.status === 422 ? (
          <Link
            className="button button--secondary"
            to={`/attempts/new?route_variant_id=${encodeURIComponent(draft.body.route_variant_id)}`}
          >
            Log a new attempt
          </Link>
        ) : null}
        {draft.state !== 'sending' ? (
          <button type="button" className="link-button" onClick={onDiscard}>
            Discard draft
          </button>
        ) : null}
      </div>
    </li>
  );
};

/**
 * /attempts (BC-0118): the signed-in user's drafts that have not reached the server on
 * top, then the server's history newest first with "Load more" on next_cursor. GET /v1/me
 * runs here too, so a tab that has not identified its user yet (fresh reload) learns the
 * owner before any draft is listed or retried (BC-0123 F4).
 */
export const AttemptsPage = () => {
  const hasToken = tokenStore.get() !== null;
  const location = useLocation();
  const queryClient = useQueryClient();
  useMe({ enabled: hasToken });
  const attempts = useAttempts(hasToken);
  const drafts = useDrafts();
  const [savedId] = useState<string | null>(
    () => (location.state as { saved?: string | null } | null)?.saved ?? null,
  );
  useReconnectSync();

  const rows = attempts.data?.pages.flatMap((page) => page.items) ?? [];

  // Synced drafts leave the device once the server list shows them.
  useEffect(() => {
    const served = new Set(rows.map((row) => row.attempt_id));
    for (const draft of drafts) {
      if (
        draft.state === 'synced' &&
        draft.attempt &&
        served.has(draft.attempt.attempt_id)
      )
        void draftStore.remove(draft.draft_id);
    }
  }, [drafts, rows]);

  if (!hasToken) return <Navigate to="/onboarding" replace />;

  const pending = drafts.filter((d) => d.state !== 'synced');
  const retry = async (draftId: string): Promise<void> => {
    const result = await submitDraft(draftId);
    if (result.state === 'synced')
      await queryClient.invalidateQueries({ queryKey: attemptsQueryKey });
  };

  return (
    <article className="page attempts">
      <div className="attempts__header">
        <p className="page__eyebrow">History</p>
        <h1>Attempt log</h1>
        <p className="page__lede">
          Every go you log, newest first. Correct a mistake or retract an entry;
          each change is a new revision and nothing is ever double-submitted.
        </p>
        <p>
          <Link className="button" to="/explore">
            Log an attempt
          </Link>
        </p>
      </div>

      {pending.length > 0 ? (
        <section className="attempts__section" aria-labelledby="drafts-heading">
          <h2 id="drafts-heading">Waiting to save ({pending.length})</h2>
          <ul className="attempts__list">
            {pending.map((draft) => (
              <DraftItem
                key={draft.draft_id}
                draft={draft}
                onRetry={() => void retry(draft.draft_id)}
                onDiscard={() => void draftStore.remove(draft.draft_id)}
              />
            ))}
          </ul>
        </section>
      ) : null}

      <section className="attempts__section" aria-labelledby="history-heading">
        <h2 id="history-heading">History</h2>
        {attempts.isPending ? (
          <div aria-busy="true">
            <p role="status" className="field__hint">
              Loading your attempts
            </p>
            <ul className="attempts__list" aria-hidden="true">
              {Array.from({ length: SKELETON_ROWS }, (_, i) => (
                <li className="attempt attempt--skeleton" key={i}>
                  <span className="skeleton" style={{ width: '50%' }} />
                  <span className="skeleton" style={{ width: '80%' }} />
                </li>
              ))}
            </ul>
          </div>
        ) : attempts.isError && rows.length === 0 ? (
          <ApiErrorBanner
            error={attempts.error}
            title="Your attempts couldn't be loaded."
            onRetry={() => void attempts.refetch()}
          />
        ) : rows.length === 0 ? (
          <div className="sheet attempts__empty">
            <p className="banner__title">No attempts logged yet.</p>
            <p>
              When you log attempts they appear here, and Beacon uses them to
              avoid repeating routes too soon. Personal send predictions
              aren&apos;t available.
            </p>
          </div>
        ) : (
          <>
            <p role="status" className="field__hint">
              {rows.length} attempt{rows.length === 1 ? '' : 's'} loaded
              {attempts.hasNextPage ? ', more available' : ''}
            </p>
            <ul className="attempts__list">
              {rows.map((attempt) => (
                <AttemptRow
                  key={attempt.attempt_id}
                  attempt={attempt}
                  isJustSaved={attempt.attempt_id === savedId}
                />
              ))}
            </ul>
            {attempts.isError ? (
              <ApiErrorBanner
                error={attempts.error}
                title="More attempts couldn't be loaded."
                onRetry={() => void attempts.fetchNextPage()}
              />
            ) : null}
            {attempts.hasNextPage ? (
              <button
                type="button"
                className="button button--secondary"
                onClick={() => void attempts.fetchNextPage()}
                disabled={attempts.isFetchingNextPage}
                aria-busy={attempts.isFetchingNextPage}
              >
                {attempts.isFetchingNextPage ? 'Loading more' : 'Load more'}
              </button>
            ) : (
              <p className="field__hint">End of your history.</p>
            )}
          </>
        )}
      </section>
    </article>
  );
};
