import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';
import { decisionQueryKey } from '../decision/useDecision';
import type { RecommendationRequest } from './session-params';

export type RecommendationResponse =
  components['schemas']['RecommendationResponse'];

/**
 * POST /v1/me/sessions/recommendations. Every call records a decision server-side, so
 * nothing retries automatically; the person presses "Try again". The response is also
 * cached as the decision so the detail page opens without a second request.
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
