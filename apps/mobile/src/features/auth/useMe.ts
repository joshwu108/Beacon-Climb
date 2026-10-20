import {
  queryOptions,
  useQuery,
  type QueryClient,
} from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';
import { tokenStore } from '../../auth/token-store';

export type Me = components['schemas']['Me'];
export type Preferences = components['schemas']['Preferences'];

export const meQueryKey = ['me'] as const;

/** GET /v1/me: consent and preferences; each answer records the user id with the token. */
export const meQueryOptions = () =>
  queryOptions({
    queryKey: meQueryKey,
    queryFn: async () => {
      const me = await call<Me>(() => api.GET('/v1/me'));
      tokenStore.setUserId(me.user_id);
      return me;
    },
  });

export const useMe = (enabled = true) =>
  useQuery({ ...meQueryOptions(), enabled });

export const patchCachedMe = (
  queryClient: QueryClient,
  patch: Partial<Pick<Me, 'consent' | 'preferences'>>,
): void => {
  queryClient.setQueryData<Me>(meQueryKey, (current) =>
    current === undefined ? current : { ...current, ...patch },
  );
};

export const hasActiveConsent = (me: Me): boolean =>
  me.consent !== null && me.consent.revoked_at === null;
