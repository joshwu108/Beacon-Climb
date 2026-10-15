import { useQuery } from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';

export type ConsentDocument = components['schemas']['ConsentDocument'];

export const consentDocumentQueryKey = ['consent', 'current'] as const;

/** GET /v1/consent/current (public). The versioned text the consent step must show. */
export const useConsentDocument = () =>
  useQuery({
    queryKey: consentDocumentQueryKey,
    queryFn: () => call<ConsentDocument>(() => api.GET('/v1/consent/current')),
  });
