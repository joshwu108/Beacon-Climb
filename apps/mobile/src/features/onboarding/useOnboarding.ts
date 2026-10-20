import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';
import { patchCachedMe, type Preferences } from '../auth/useMe';

export type ConsentDocument = components['schemas']['ConsentDocument'];
export type ConsentRequest = components['schemas']['ConsentRequest'];

/** GET /v1/consent/current (public): the versioned text the consent step shows in full. */
export const useConsentDocument = () =>
  useQuery({
    queryKey: ['consent', 'current'],
    queryFn: () => call<ConsentDocument>(() => api.GET('/v1/consent/current')),
  });

/** POST /v1/me/consent, mirrored into the cached profile (the 204 has no body). */
export const useRecordConsent = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ConsentRequest) =>
      call<undefined>(() => api.POST('/v1/me/consent', { body })),
    onSuccess: (_data, body) =>
      patchCachedMe(queryClient, {
        consent: {
          version: body.consent_version,
          consented_at: new Date().toISOString(),
          revoked_at: null,
          scopes: body.scopes,
          text_sha256: body.text_sha256,
        },
      }),
  });
};

/** PUT /v1/me/preferences; the response is the saved Preferences. */
export const useSavePreferences = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: Preferences) =>
      call<Preferences>(() => api.PUT('/v1/me/preferences', { body })),
    onSuccess: (preferences) => patchCachedMe(queryClient, { preferences }),
  });
};
