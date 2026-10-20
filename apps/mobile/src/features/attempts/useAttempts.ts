import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
  type InfiniteData,
  type QueryClient,
} from '@tanstack/react-query';
import { randomUUID } from 'expo-crypto';
import { useState } from 'react';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';

export type Attempt = components['schemas']['Attempt'];
export type AttemptList = components['schemas']['AttemptList'];
export type AttemptCreate = components['schemas']['AttemptCreate'];
export type AttemptPatch = components['schemas']['AttemptPatch'];

export const PAGE_LIMIT = 20;
export const attemptsQueryKey = ['attempts', 'list'] as const;

/** GET /v1/me/attempts, newest first. */
export const useAttempts = (enabled = true) =>
  useInfiniteQuery({
    queryKey: attemptsQueryKey,
    queryFn: ({ pageParam }) =>
      call<AttemptList>(() =>
        api.GET('/v1/me/attempts', {
          params: {
            query: {
              limit: PAGE_LIMIT,
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor ?? undefined,
    enabled,
  });

export const replaceCachedAttempt = (
  queryClient: QueryClient,
  attempt: Attempt,
): void => {
  queryClient.setQueryData<InfiniteData<AttemptList>>(
    attemptsQueryKey,
    (data) =>
      data === undefined
        ? data
        : {
            ...data,
            pages: data.pages.map((page) => ({
              ...page,
              items: page.items.map((row) =>
                row.attempt_id === attempt.attempt_id ? attempt : row,
              ),
            })),
          },
  );
};

/**
 * POST /v1/me/attempts. One Idempotency-Key per form: a retry after a network failure (or
 * a double tap) re-sends the same key, so the server records the attempt at most once.
 * Unlike apps/web there is no offline draft queue on this device (handoff: skipped).
 */
export const useCreateAttempt = () => {
  const queryClient = useQueryClient();
  const [idempotencyKey] = useState(() => randomUUID());
  const mutation = useMutation({
    mutationFn: (body: AttemptCreate) =>
      call<Attempt>(() =>
        api.POST('/v1/me/attempts', {
          params: { header: { 'Idempotency-Key': idempotencyKey } },
          body,
        }),
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: attemptsQueryKey }),
  });
  return { ...mutation, idempotencyKey };
};

export interface PatchInput {
  readonly attemptId: string;
  readonly revision: number;
  readonly patch: AttemptPatch;
}

/** PATCH /v1/me/attempts/{attempt_id} with If-Match (optimistic concurrency). */
export const usePatchAttempt = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ attemptId, revision, patch }: PatchInput) =>
      call<Attempt>(() =>
        api.PATCH('/v1/me/attempts/{attempt_id}', {
          params: {
            path: { attempt_id: attemptId },
            header: { 'If-Match': `"${revision}"` },
          },
          body: patch,
        }),
      ),
    onSuccess: (attempt) => replaceCachedAttempt(queryClient, attempt),
  });
};

/** GET /v1/me/attempts/{attempt_id} (edit screen; after a REVISION_CONFLICT). */
export const fetchAttempt = (attemptId: string): Promise<Attempt> =>
  call<Attempt>(() =>
    api.GET('/v1/me/attempts/{attempt_id}', {
      params: { path: { attempt_id: attemptId } },
    }),
  );
