import type { components } from '../../api/generated/schema';
import { GOALS } from '../onboarding/goals';

export type Goal = components['schemas']['Goal'];
export type RecommendationRequest =
  components['schemas']['RecommendationRequest'];

/** UI range for max_routes (PRD 3.1); the contract allows 1..20. */
export const MIN_ROUTES = 3;
export const MAX_ROUTES = 6;
export const DEFAULT_ROUTES = 4;
/** Contract cap on exclude_route_variant_ids. */
export const MAX_EXCLUDED = 100;

export interface SessionParams {
  /** Client-generated session id, kept in the URL so a reload reuses it. */
  readonly sessionId: string | null;
  readonly goal: Goal;
  readonly routes: number;
}

export const isGoal = (value: string | null | undefined): value is Goal =>
  GOALS.some((g) => g.id === value);

export const clampRoutes = (value: number): number =>
  Number.isFinite(value)
    ? Math.min(MAX_ROUTES, Math.max(MIN_ROUTES, Math.round(value)))
    : DEFAULT_ROUTES;

/** Read the builder state from the URL; the goal defaults to the first preferred goal. */
export const parseSessionParams = (
  params: URLSearchParams,
  preferredGoals: readonly string[],
): SessionParams => {
  const goalParam = params.get('goal');
  const preferred = preferredGoals.find(isGoal);
  return {
    sessionId: params.get('session_id'),
    goal: isGoal(goalParam) ? goalParam : (preferred ?? 'TECHNIQUE'),
    routes: clampRoutes(Number(params.get('routes') ?? DEFAULT_ROUTES)),
  };
};

export const toSessionSearch = (
  current: URLSearchParams,
  next: Partial<SessionParams>,
): URLSearchParams => {
  const params = new URLSearchParams(current);
  if (next.sessionId !== undefined && next.sessionId !== null)
    params.set('session_id', next.sessionId);
  if (next.goal !== undefined) params.set('goal', next.goal);
  if (next.routes !== undefined) params.set('routes', String(next.routes));
  return params;
};

export const buildRequest = (input: {
  readonly boardId: string;
  readonly angleDeg: number;
  readonly goal: Goal;
  readonly maxRoutes: number;
  readonly sessionId: string;
  readonly excluded: readonly string[];
}): RecommendationRequest => ({
  board_id: input.boardId,
  angle_deg: input.angleDeg,
  goal: input.goal,
  max_routes: Math.min(20, Math.max(1, input.maxRoutes)),
  session_id: input.sessionId,
  exclude_route_variant_ids: input.excluded.slice(-MAX_EXCLUDED),
});
