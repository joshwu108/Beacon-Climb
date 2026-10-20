import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import type { RoutesQuery } from '@web/features/explore/filters';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';

export type RouteList = components['schemas']['RouteList'];
export type RouteVariant = components['schemas']['RouteVariant'];
export type RouteVariantSummary = components['schemas']['RouteVariantSummary'];
export type SimilarRoutes = components['schemas']['SimilarRoutes'];

/** GET /v1/routes, one page per cursor. */
export const useRoutes = (query: RoutesQuery | null) =>
  useInfiniteQuery({
    queryKey: ['routes', query],
    queryFn: ({ pageParam }) =>
      call<RouteList>(() =>
        api.GET('/v1/routes', {
          params: {
            query: { ...query!, ...(pageParam ? { cursor: pageParam } : {}) },
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor ?? undefined,
    enabled: query !== null,
  });

/** GET /v1/routes/{route_variant_id}: placements, tags and grade evidence. */
export const useRoute = (routeVariantId: string) =>
  useQuery({
    queryKey: ['routes', 'detail', routeVariantId],
    queryFn: () =>
      call<RouteVariant>(() =>
        api.GET('/v1/routes/{route_variant_id}', {
          params: { path: { route_variant_id: routeVariantId } },
        }),
      ),
    enabled: routeVariantId !== '',
  });

export const SIMILAR_LIMIT = 5;

/** GET /v1/routes/{route_variant_id}/similar ("More like this", contract 1.3.0). */
export const useSimilarRoutes = (routeVariantId: string, enabled = true) =>
  useQuery({
    queryKey: ['routes', 'similar', routeVariantId, SIMILAR_LIMIT],
    queryFn: () =>
      call<SimilarRoutes>(() =>
        api.GET('/v1/routes/{route_variant_id}/similar', {
          params: {
            path: { route_variant_id: routeVariantId },
            query: { limit: SIMILAR_LIMIT },
          },
        }),
      ),
    enabled: enabled && routeVariantId !== '',
  });

/** Cosine distance (0..2) as plain words; it is a model's layout comparison, not advice. */
export const describeDistance = (distance: number): string =>
  distance < 0.15
    ? 'very close layout'
    : distance < 0.4
      ? 'similar layout'
      : 'loosely related';
