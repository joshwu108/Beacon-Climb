import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useEffect, useSyncExternalStore } from 'react';
import { api, call } from '../../api/client';
import { isApiError } from '../../api/errors';
import {
  draftStore,
  type Attempt,
  type AttemptDraft,
  type DraftError,
} from './draft-store';
import { attemptsQueryKey } from './useAttempts';

const toDraftError = (error: unknown): DraftError =>
  isApiError(error)
    ? {
        status: error.status,
        code: error.code,
        message: error.message,
        request_id: error.requestId,
        field_errors: error.fieldErrors,
      }
    : {
        status: 0,
        code: 'UNKNOWN',
        message: error instanceof Error ? error.message : 'Request failed',
        request_id: null,
        field_errors: [],
      };

/** Network, 5xx and 429 may be retried with the same key; everything else needs the person. */
export const isRetryable = (error: DraftError | null): boolean =>
  error !== null &&
  (error.status === 0 || error.status === 429 || error.status >= 500);

const inFlight = new Map<string, Promise<AttemptDraft>>();

const send = async (draftId: string): Promise<AttemptDraft> => {
  await draftStore.ready();
  const draft = draftStore.get(draftId);
  if (draft === undefined) throw new Error(`Unknown draft ${draftId}`);
  if (draft.state === 'synced') return draft;
  await draftStore.put({ ...draft, state: 'sending', error: null });
  try {
    const attempt = await call<Attempt>(() =>
      api.POST('/v1/me/attempts', {
        params: { header: { 'Idempotency-Key': draft.idempotency_key } },
        body: draft.body,
      }),
    );
    // 201 and the 200 replay are the same outcome: the server has this submission.
    const synced: AttemptDraft = {
      ...draft,
      state: 'synced',
      attempt,
      error: null,
    };
    await draftStore.put(synced);
    return synced;
  } catch (error) {
    const failed: AttemptDraft = {
      ...draft,
      state: 'failed',
      error: toDraftError(error),
    };
    await draftStore.put(failed);
    return failed;
  }
};

/**
 * POST /v1/me/attempts for one draft with its stored Idempotency-Key. A draft already
 * in flight returns the same promise, so a double submit sends at most one request.
 */
export const submitDraft = (draftId: string): Promise<AttemptDraft> => {
  const existing = inFlight.get(draftId);
  if (existing !== undefined) return existing;
  const pending = send(draftId).finally(() => inFlight.delete(draftId));
  inFlight.set(draftId, pending);
  return pending;
};

/** Retry every failed draft whose failure was transient; once per call, same keys. */
export const syncFailedDrafts = async (
  queryClient: QueryClient,
): Promise<readonly AttemptDraft[]> => {
  await draftStore.ready();
  const due = draftStore
    .list()
    .filter((d) => d.state === 'failed' && isRetryable(d.error));
  const results = await Promise.all(due.map((d) => submitDraft(d.draft_id)));
  if (results.some((d) => d.state === 'synced')) {
    await queryClient.invalidateQueries({ queryKey: attemptsQueryKey });
  }
  return results;
};

/** Drafts from the store, loaded on first use and updated on every change. */
export const useDrafts = (): readonly AttemptDraft[] => {
  useEffect(() => {
    void draftStore.ready();
  }, []);
  return useSyncExternalStore(
    draftStore.subscribe,
    draftStore.list,
    draftStore.list,
  );
};

/**
 * Sync-on-reconnect: the `online` event retries failed drafts once (same keys). Drafts
 * left behind by an earlier visit are retried once on mount when the browser is online.
 */
export const useReconnectSync = (): void => {
  const queryClient = useQueryClient();
  useEffect(() => {
    const onOnline = (): void => {
      void syncFailedDrafts(queryClient);
    };
    if (navigator.onLine) onOnline();
    window.addEventListener('online', onOnline);
    return () => window.removeEventListener('online', onOnline);
  }, [queryClient]);
};
