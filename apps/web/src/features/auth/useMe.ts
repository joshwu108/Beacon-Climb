import {
  queryOptions,
  useQuery,
  type QueryClient,
} from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';
import { recordSignedInUser } from './signed-in-user';

export type Me = components['schemas']['Me'];
export type Consent = components['schemas']['Consent'];
export type Preferences = components['schemas']['Preferences'];

export const meQueryKey = ['me'] as const;

/**
 * GET /v1/me: the signed-in user's consent and preferences. Every answer also records
 * the user id for this tab (token store), which is what ties attempt drafts to a person.
 */
export const meQueryOptions = () =>
  queryOptions({
    queryKey: meQueryKey,
    queryFn: async () => {
      const me = await call<Me>(() => api.GET('/v1/me'));
      await recordSignedInUser(me.user_id);
      return me;
    },
  });

export const useMe = (options: { enabled?: boolean } = {}) =>
  useQuery({ ...meQueryOptions(), enabled: options.enabled ?? true });

/** Update the cached profile after a write (consent, preferences) without a refetch. */
export const patchCachedMe = (
  queryClient: QueryClient,
  patch: Partial<Pick<Me, 'consent' | 'preferences'>>,
): void => {
  queryClient.setQueryData<Me>(meQueryKey, (current) =>
    current === undefined ? current : { ...current, ...patch },
  );
};

/** Consent counts only when it exists and has not been revoked. */
export const hasActiveConsent = (me: Me): boolean =>
  me.consent !== null && me.consent.revoked_at === null;
