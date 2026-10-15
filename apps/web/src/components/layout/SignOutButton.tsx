import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { signOut } from '../../features/auth/sign-out';
import { tokenStore } from '../../features/auth/token-store';

/**
 * Header "Sign out" (BC-0118): shown only while a token exists. Clears the token, the
 * query cache and the attempt drafts (lazy import keeps the draft store out of the
 * shell chunk), then returns to onboarding.
 */
export const SignOutButton = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [isBusy, setBusy] = useState(false);
  // Re-evaluated on every navigation so the button appears after sign-in.
  const hasToken = tokenStore.get() !== null;
  if (!hasToken || location.pathname.startsWith('/onboarding/account')) {
    return null;
  }
  const handle = async (): Promise<void> => {
    setBusy(true);
    const { clearDrafts } = await import('../../features/attempts/draft-store');
    await signOut(queryClient, clearDrafts);
    setBusy(false);
    void navigate('/onboarding', { replace: true });
  };
  return (
    <button
      type="button"
      className="button button--secondary shell__sign-out"
      onClick={() => void handle()}
      disabled={isBusy}
    >
      {isBusy ? 'Signing out' : 'Sign out'}
    </button>
  );
};
