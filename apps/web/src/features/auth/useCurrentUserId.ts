import { useSyncExternalStore } from 'react';
import { tokenStore } from './token-store';

/** The signed-in user's id for this tab, or null until GET /v1/me has answered. */
export const useCurrentUserId = (): string | null =>
  useSyncExternalStore(
    tokenStore.subscribe,
    tokenStore.getUserId,
    tokenStore.getUserId,
  );
