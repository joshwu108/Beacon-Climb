/**
 * Deterministic recommendation lineups and attempt listing for the MSW handlers
 * (BC-0117 / BC-0118). Nothing here is a ranking: the lineup is a slice of the synthetic
 * catalog chosen by a hash of `session_id`, so the same session always gets the same
 * routes, dismissed ids are skipped, and the UI states (cold start, reason codes,
 * uncertainty labels) can be seen. The real policy lives in services/api.
 */
import type { components } from '../api/generated/schema';
import { CATALOG, LOW_EVIDENCE_THRESHOLD, toSummary } from './catalog';
import createRecommendations from '../../../../contracts/examples/createRecommendations/response.json';

type Attempt = components['schemas']['Attempt'];
type Recommendation = components['schemas']['Recommendation'];
type RecommendationResponse = components['schemas']['RecommendationResponse'];
type ReasonCode = components['schemas']['ReasonCode'];

/** Below this many ACTIVE attempts the mock reports cold_start (the real threshold is policy-owned). */
export const COLD_START_BELOW = 5;

const hash = (text: string): number => {
  let h = 0;
  for (const char of text) h = (h * 31 + char.charCodeAt(0)) >>> 0;
  return h;
};

const round3 = (n: number): number => Math.round(n * 1000) / 1000;

export interface LineupInput {
  readonly decisionId: string;
  readonly angleDeg: number;
  readonly maxRoutes: number;
  readonly sessionId: string;
  readonly exclude: readonly string[];
  readonly attempts: readonly Attempt[];
}

/** Lineup for a session: catalog routes at the angle, rotated by session_id, minus excludes. */
export const buildRecommendations = (
  input: LineupInput,
): RecommendationResponse => {
  const excluded = new Set(input.exclude);
  const attempted = new Set(
    input.attempts
      .filter((a) => a.status === 'ACTIVE')
      .map((a) => a.route_variant_id),
  );
  const coldStart =
    input.attempts.filter((a) => a.status === 'ACTIVE').length <
    COLD_START_BELOW;
  const atAngle = CATALOG.filter((v) => v.angle_deg === input.angleDeg).map(
    toSummary,
  );
  const offset =
    atAngle.length === 0 ? 0 : hash(input.sessionId) % atAngle.length;
  const rotated = [...atAngle.slice(offset), ...atAngle.slice(0, offset)];
  const picked = rotated
    .filter((route) => !excluded.has(route.route_variant_id))
    .slice(0, input.maxRoutes);
  const widened = picked.length < input.maxRoutes;

  const recommendations: Recommendation[] = picked.map((route, index) => {
    const reasons: ReasonCode[] = ['COMPATIBLE_ANGLE', 'GOAL_CHALLENGE_MATCH'];
    if (!attempted.has(route.route_variant_id)) reasons.push('NOVEL_ROUTE');
    if (route.grade_observation_count >= LOW_EVIDENCE_THRESHOLD)
      reasons.push('WELL_OBSERVED_GRADE');
    if (coldStart) reasons.push('COLD_START_HEURISTIC');
    if (widened) reasons.push('LOW_INVENTORY_WIDENED');
    return {
      route_variant_id: route.route_variant_id,
      route,
      position: index + 1,
      rank_score: round3(0.9 - index * 0.07),
      reason_codes: reasons,
      predicted_send_probability: null,
      uncertainty_label: 'INSUFFICIENT_HISTORY',
    };
  });

  return {
    decision_id: input.decisionId,
    policy_version: createRecommendations.body.policy_version,
    model_version: createRecommendations.body.model_version,
    feature_as_of: createRecommendations.body.feature_as_of,
    cold_start: coldStart,
    recommendations,
    fallback_used: false,
  };
};

/* listAttempts (A11): newest first by occurred_at desc, then attempt_id desc. Cursor is
   base64url of "<occurred_at>|<attempt_id>" of the last row served. */
const toBase64Url = (text: string): string =>
  btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

const fromBase64Url = (text: string): string | null => {
  try {
    const padded = text.replace(/-/g, '+').replace(/_/g, '/');
    return atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  } catch {
    return null;
  }
};

const compareNewestFirst = (a: Attempt, b: Attempt): number => {
  if (a.occurred_at !== b.occurred_at)
    return a.occurred_at < b.occurred_at ? 1 : -1;
  return a.attempt_id < b.attempt_id ? 1 : a.attempt_id > b.attempt_id ? -1 : 0;
};

export interface AttemptQuery {
  readonly cursor?: string;
  readonly limit: number;
  readonly status?: Attempt['status'];
}

export type AttemptQueryResult =
  | {
      readonly kind: 'ok';
      readonly items: Attempt[];
      readonly nextCursor: string | null;
    }
  | { readonly kind: 'invalid_cursor' };

export const queryAttempts = (
  attempts: readonly Attempt[],
  query: AttemptQuery,
): AttemptQueryResult => {
  const sorted = attempts
    .filter((a) => query.status === undefined || a.status === query.status)
    .sort(compareNewestFirst);
  let start = 0;
  if (query.cursor !== undefined) {
    const decoded = fromBase64Url(query.cursor);
    const separator = decoded?.lastIndexOf('|') ?? -1;
    const position =
      decoded === null || separator <= 0
        ? -1
        : sorted.findIndex(
            (a) =>
              a.occurred_at === decoded.slice(0, separator) &&
              a.attempt_id === decoded.slice(separator + 1),
          );
    if (position < 0) return { kind: 'invalid_cursor' };
    start = position + 1;
  }
  const items = sorted.slice(start, start + query.limit);
  const last = items[items.length - 1];
  const hasMore = start + query.limit < sorted.length;
  return {
    kind: 'ok',
    items,
    nextCursor:
      hasMore && last !== undefined
        ? toBase64Url(`${last.occurred_at}|${last.attempt_id}`)
        : null,
  };
};
