import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';
import type { RoutesQuery } from './filters';

export type RouteList = components['schemas']['RouteList'];
export type RouteVariantSummary = components['schemas']['RouteVariantSummary'];
export type RouteVariant = components['schemas']['RouteVariant'];

export const routesQueryKey = (query: RoutesQuery) =>
  ['routes', query] as const;

/** GET /v1/routes, one page per cursor; `next_cursor: null` ends the list. */
export const useRoutes = (query: RoutesQuery | null) =>
  useInfiniteQuery({
    queryKey: routesQueryKey(query ?? { board_id: '', angle_deg: 0, limit: 0 }),
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

export const routeQueryKey = (routeVariantId: string) =>
  ['routes', 'detail', routeVariantId] as const;

/** GET /v1/routes/{route_variant_id}: placements, tags and grade evidence. */
export const useRoute = (routeVariantId: string) =>
  useQuery({
    queryKey: routeQueryKey(routeVariantId),
    queryFn: () =>
      call<RouteVariant>(() =>
        api.GET('/v1/routes/{route_variant_id}', {
          params: { path: { route_variant_id: routeVariantId } },
        }),
      ),
  });
