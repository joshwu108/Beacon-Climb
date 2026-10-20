/**
 * Mock for GET /v1/routes/{route_variant_id}/similar (listSimilarRoutes, contract 1.3.0),
 * which apps/web's MSW handlers do not cover yet. Kept here, beside the shared handlers,
 * instead of editing apps/web.
 *
 * The committed fixture (contracts/examples/listSimilarRoutes) is served verbatim for its
 * own route. Every other route gets neighbours at the same board and angle from the
 * synthetic catalog, ordered by a hold-overlap distance (1 - Jaccard over hold ids). That
 * number is a deterministic stand-in so the "More like this" states can be seen; it is not
 * a model output and not a measurement.
 */
import { http, HttpResponse } from 'msw';
import listSimilarRoutes from '../../../../contracts/examples/listSimilarRoutes/response.json';
import listSimilarRoutesRequest from '../../../../contracts/examples/listSimilarRoutes/request.json';
import { CATALOG, findVariant, toSummary } from '@web/mocks/catalog';
import type { components } from '../api/generated/schema';

type SimilarRoutes = components['schemas']['SimilarRoutes'];
type RouteVariant = components['schemas']['RouteVariant'];

export const DEFAULT_SIMILAR_LIMIT = 10;
export const MAX_SIMILAR_LIMIT = 50;
/** model_version the mock reports for generated neighbours (the fixture's own label). */
export const MOCK_SIMILAR_MODEL_VERSION = listSimilarRoutes.body.model_version;

const FIXTURE: SimilarRoutes = listSimilarRoutes.body as SimilarRoutes;
const FIXTURE_ROUTE_ID = listSimilarRoutesRequest.path_params.route_variant_id;

const round2 = (n: number): number => Math.round(n * 100) / 100;

/** 1 - |A ∩ B| / |A ∪ B| over the hold ids two routes use; 0 = same holds, 1 = none shared. */
export const holdOverlapDistance = (
  a: RouteVariant,
  b: RouteVariant,
): number => {
  const left = new Set(a.placements.map((p) => p.hold_id));
  const right = new Set(b.placements.map((p) => p.hold_id));
  const union = new Set([...left, ...right]);
  if (union.size === 0) return 1;
  let shared = 0;
  for (const id of left) if (right.has(id)) shared += 1;
  return round2(1 - shared / union.size);
};

export const similarFor = (
  variant: RouteVariant,
  limit: number,
): SimilarRoutes => {
  if (variant.route_variant_id === FIXTURE_ROUTE_ID) {
    return { ...FIXTURE, items: FIXTURE.items.slice(0, limit) };
  }
  const items = CATALOG.filter(
    (other) =>
      other.route_variant_id !== variant.route_variant_id &&
      other.board_id === variant.board_id &&
      other.angle_deg === variant.angle_deg,
  )
    .map((other) => ({
      route: toSummary(other),
      distance: holdOverlapDistance(variant, other),
    }))
    .sort(
      (x, y) =>
        x.distance - y.distance ||
        (x.route.route_variant_id < y.route.route_variant_id ? -1 : 1),
    )
    .slice(0, limit);
  return {
    route_variant_id: variant.route_variant_id,
    model_version: MOCK_SIMILAR_MODEL_VERSION,
    items,
  };
};

const envelope = (
  status: number,
  code: string,
  message: string,
  field?: string,
) =>
  HttpResponse.json(
    {
      code,
      message,
      request_id: 'mock-similar',
      field_errors: field
        ? [{ field, code: 'range', message: 'must be 1..50' }]
        : [],
    },
    { status, headers: { 'X-Request-Id': 'mock-similar' } },
  );

export const similarHandler = http.get(
  '*/v1/routes/:routeVariantId/similar',
  ({ request, params }) => {
    if (!/^Bearer\s+\S+/.test(request.headers.get('Authorization') ?? '')) {
      return envelope(401, 'UNAUTHENTICATED', 'Authentication required');
    }
    const limitText = new URL(request.url).searchParams.get('limit');
    const limit =
      limitText === null ? DEFAULT_SIMILAR_LIMIT : Number(limitText);
    if (!Number.isInteger(limit) || limit < 1 || limit > MAX_SIMILAR_LIMIT) {
      return envelope(
        422,
        'VALIDATION_FAILED',
        'Request failed validation',
        'limit',
      );
    }
    const variant = findVariant(String(params.routeVariantId));
    if (variant === null) return envelope(404, 'NOT_FOUND', 'Not found');
    return HttpResponse.json(similarFor(variant, limit), {
      status: 200,
      headers: { 'X-Request-Id': 'mock-similar' },
    });
  },
);
