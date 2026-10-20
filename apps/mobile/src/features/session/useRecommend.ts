import { useMutation, useQueryClient } from '@tanstack/react-query';
import { buildRequest } from '@web/features/session/session-params';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';
import { decisionQueryKey } from '../decision/useDecision';
import type {
  Goal,
  PolicyVersion,
  RecommendationResponse,
} from './session-store';

export type RecommendationRequest =
  components['schemas']['RecommendationRequest'];

export interface RecommendInput {
  readonly boardId: string;
  readonly angleDeg: number;
  readonly goal: Goal;
  readonly maxRoutes: number;
  readonly sessionId: string;
  readonly excluded: readonly string[];
  readonly policy: PolicyVersion;
}

/** The request body: apps/web's buildRequest plus the 1.3.0 policy_version. */
export const toRecommendationRequest = (
  input: RecommendInput,
): RecommendationRequest => ({
  ...buildRequest(input),
  policy_version: input.policy,
});

/**
 * POST /v1/me/sessions/recommendations. Every call records a decision, so it never retries
 * on its own. The response is cached as the decision so "Why" opens without a request.
 */
export const useRecommend = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RecommendationRequest) =>
      call<RecommendationResponse>(() =>
        api.POST('/v1/me/sessions/recommendations', { body }),
      ),
    onSuccess: (response, body) => {
      queryClient.setQueryData(decisionQueryKey(response.decision_id), {
        ...response,
        created_at: new Date().toISOString(),
        request: body,
      });
    },
  });
};
