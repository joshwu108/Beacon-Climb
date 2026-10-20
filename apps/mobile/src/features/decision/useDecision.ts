import { useQuery } from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';

export type Decision = components['schemas']['Decision'];
export type Recommendation = components['schemas']['Recommendation'];

export const decisionQueryKey = (decisionId: string) =>
  ['decisions', decisionId] as const;

/** GET /v1/me/decisions/{decision_id}. Decisions are immutable, so never stale. */
export const useDecision = (decisionId: string, enabled = true) =>
  useQuery({
    queryKey: decisionQueryKey(decisionId),
    queryFn: () =>
      call<Decision>(() =>
        api.GET('/v1/me/decisions/{decision_id}', {
          params: { path: { decision_id: decisionId } },
        }),
      ),
    staleTime: Infinity,
    enabled,
  });

export const byPosition = (
  items: readonly Recommendation[],
): Recommendation[] => [...items].sort((a, b) => a.position - b.position);
