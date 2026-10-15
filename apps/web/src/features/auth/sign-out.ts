import type { QueryClient } from '@tanstack/react-query';
import { api } from '../../api/client';
import { tokenStore } from './token-store';

/**
 * Sign out: DELETE /v1/auth/session, then drop everything this device holds for the
 * person (token, cached queries and the attempt drafts, which carry notes). The local
 * clean-up runs even when the request fails, so a lost connection never leaves the
 * next person on the device signed in; the failure is reported to the caller.
 */
export const signOut = async (
  queryClient: QueryClient,
  clearLocal: () => Promise<void>,
): Promise<{ ok: boolean }> => {
  let ok = true;
  try {
    const result = await api.DELETE('/v1/auth/session');
    ok = result.response.ok;
  } catch {
    ok = false;
  }
  tokenStore.clear();
  queryClient.clear();
  await clearLocal();
  return { ok };
};
