import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';
import { patchCachedMe, type Preferences } from '../auth/useMe';

export type ConsentRequest = components['schemas']['ConsentRequest'];

/** POST /v1/me/consent, then mirror the record into the cached profile (204 has no body). */
export const useRecordConsent = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ConsentRequest) =>
      call<undefined>(() => api.POST('/v1/me/consent', { body })),
    onSuccess: (_data, body) => {
      patchCachedMe(queryClient, {
        consent: {
          version: body.consent_version,
          consented_at: new Date().toISOString(),
          revoked_at: null,
          scopes: body.scopes,
          text_sha256: body.text_sha256,
        },
      });
    },
  });
};

/** PUT /v1/me/preferences; the response is the saved Preferences. */
export const useSavePreferences = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: Preferences) =>
      call<Preferences>(() => api.PUT('/v1/me/preferences', { body })),
    onSuccess: (preferences) => {
      patchCachedMe(queryClient, { preferences });
    },
  });
};
