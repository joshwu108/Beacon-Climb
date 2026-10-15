import { tokenStore } from './token-store';

/**
 * Called whenever GET /v1/me answers (BC-0123 F4). Remembers which user this tab's token
 * belongs to and, the first time that is known in the tab or whenever it changes, deletes
 * every attempt draft left on the device by another account (or by no account: drafts
 * made before owners were recorded). The draft store is imported lazily, like in
 * SignOutButton, so the shell chunk stays free of IndexedDB code.
 */
export const recordSignedInUser = async (userId: string): Promise<void> => {
  const previous = tokenStore.getUserId();
  tokenStore.setUserId(userId);
  if (previous === userId) return;
  const { purgeForeignDrafts } = await import('../attempts/draft-store');
  await purgeForeignDrafts(userId);
};
