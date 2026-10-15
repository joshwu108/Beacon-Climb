import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
  type InfiniteData,
  type QueryClient,
} from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';

export type Attempt = components['schemas']['Attempt'];
export type AttemptList = components['schemas']['AttemptList'];
export type AttemptPatch = components['schemas']['AttemptPatch'];

export const PAGE_LIMIT = 20;
export const attemptsQueryKey = ['attempts', 'list'] as const;

/** GET /v1/me/attempts, newest first, one page per cursor. */
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

/** GET /v1/me/attempts/{attempt_id}: the latest revision after a REVISION_CONFLICT. */
export const fetchAttempt = (attemptId: string): Promise<Attempt> =>
  call<Attempt>(() =>
    api.GET('/v1/me/attempts/{attempt_id}', {
      params: { path: { attempt_id: attemptId } },
    }),
  );

/** Replace one row in the cached pages (after a patch, retract or refresh). */
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

export interface PatchInput {
  readonly attemptId: string;
  /** The revision the change was based on; sent as `If-Match: "<revision>"`. */
  readonly revision: number;
  readonly patch: AttemptPatch;
}

/** PATCH /v1/me/attempts/{attempt_id} with optimistic concurrency. */
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
