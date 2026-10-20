import { useSyncExternalStore } from 'react';
import { tokenStore } from '../../auth/token-store';

/** True while a non-expired token is held; re-renders on sign-in and sign-out. */
export const useHasToken = (): boolean =>
  useSyncExternalStore(
    tokenStore.subscribe,
    () => tokenStore.get() !== null,
    () => tokenStore.get() !== null,
  );
