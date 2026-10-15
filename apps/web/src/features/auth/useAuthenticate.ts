import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';
import { tokenStore } from './token-store';
import { meQueryKey } from './useMe';

export type AuthResponse = components['schemas']['AuthResponse'];
export type AuthMode = 'register' | 'sign-in';

export interface AuthenticateInput {
  readonly mode: AuthMode;
  readonly username: string;
  readonly password: string;
}

/**
 * POST /v1/auth/register or POST /v1/auth/session. On success the token goes into the
 * token store (memory + sessionStorage, see token-store.ts) and any cached profile is
 * dropped so the next GET /v1/me belongs to the new session.
 */
export const useAuthenticate = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ mode, username, password }: AuthenticateInput) =>
      call<AuthResponse>(() =>
        mode === 'register'
          ? api.POST('/v1/auth/register', { body: { username, password } })
          : api.POST('/v1/auth/session', { body: { username, password } }),
      ),
    onSuccess: (auth) => {
      tokenStore.set({ token: auth.token, expiresAt: auth.expires_at });
      queryClient.removeQueries({ queryKey: meQueryKey });
    },
  });
};
