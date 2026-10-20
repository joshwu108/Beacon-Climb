import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';
import { tokenStore } from '../../auth/token-store';
import { meQueryKey } from './useMe';

export type AuthResponse = components['schemas']['AuthResponse'];
export type AuthMode = 'register' | 'sign-in';

export interface AuthenticateInput {
  readonly mode: AuthMode;
  readonly username: string;
  readonly password: string;
}

/** POST /v1/auth/register or /v1/auth/session; the token goes to the token store. */
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

/**
 * Sign out: DELETE /v1/auth/session, then drop the token and every cached query even when
 * the request fails, so a lost connection never leaves the next person signed in.
 */
export const signOut = async (queryClient: {
  clear: () => void;
}): Promise<{ ok: boolean }> => {
  let ok = true;
  try {
    const result = await api.DELETE('/v1/auth/session');
    ok = result.response.ok;
  } catch {
    ok = false;
  }
  tokenStore.clear();
  queryClient.clear();
  return { ok };
};
