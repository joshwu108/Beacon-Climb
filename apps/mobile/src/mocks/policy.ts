/**
 * apps/web's createRecommendations mock predates contract 1.3.0's optional policy_version and
 * always answers with the fixture's policy. In mock mode this app echoes the requested policy
 * (heuristic-v2 when omitted, the 1.3.0 server default) on the response and on the decision
 * the mock recorded, so the debug toggle visibly changes what the record says. The lineup
 * itself is the shared mock's deterministic slice either way: no ranking happens in mocks.
 */
import { mockState } from '@web/mocks/state';

export const DEFAULT_POLICY_VERSION = 'heuristic-v2';

export const requestedPolicy = (body: unknown): string => {
  const value =
    typeof body === 'object' && body !== null
      ? (body as { policy_version?: unknown }).policy_version
      : undefined;
  return typeof value === 'string' ? value : DEFAULT_POLICY_VERSION;
};

export const withRequestedPolicy = async (
  request: Request,
  response: Response,
): Promise<Response> => {
  if (!response.ok) return response;
  const policy = requestedPolicy(await request.json().catch(() => null));
  const body = (await response.json()) as {
    decision_id: string;
    policy_version: string;
  };
  const recorded = mockState.decisions.get(body.decision_id);
  if (recorded !== undefined) {
    mockState.decisions.set(body.decision_id, {
      ...recorded,
      policy_version: policy,
    });
  }
  return new Response(JSON.stringify({ ...body, policy_version: policy }), {
    status: response.status,
    headers: response.headers,
  });
};
