import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { isApiError } from '../../api/errors';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import type { FieldErrorMap } from '../../components/ui/field-errors';
import { tokenStore } from '../auth/token-store';
import { meQueryKey, useMe } from '../auth/useMe';
import { ConsentDocumentPanel } from './ConsentDocumentPanel';
import { OnboardingProgress } from './OnboardingProgress';
import { AccountStep } from './steps/AccountStep';
import { BoardStep } from './steps/BoardStep';
import { ConsentStep } from './steps/ConsentStep';
import { GoalsStep } from './steps/GoalsStep';
import {
  firstIncompleteStep,
  isStepId,
  resolveStep,
  type PreferencesDraft,
  type StepId,
} from './steps';
import './onboarding.css';

export { ONBOARDING_STEPS } from './steps';

const SKELETON_WIDTHS = ['45%', '90%', '70%'] as const;

const ProfileSkeleton = () => (
  <div className="sheet onboarding__form" aria-busy="true">
    <p className="visually-hidden" role="status">
      Checking your setup
    </p>
    {SKELETON_WIDTHS.map((width) => (
      <span
        className="skeleton"
        style={{ width }}
        key={width}
        aria-hidden="true"
      />
    ))}
  </div>
);

/**
 * First-run flow (BC-0115): account -> consent -> board and angle -> level and goals.
 * The step is URL state (/onboarding/:step). On mount, a stored token triggers
 * GET /v1/me and completed steps are skipped: consent present -> board, preferences
 * present -> Explore. Answers for the board step live here until the goals step saves.
 */
export const OnboardingPage = () => {
  const { step: stepParam } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [hasToken, setHasToken] = useState(() => tokenStore.get() !== null);
  const [draft, setDraft] = useState<PreferencesDraft | null>(null);
  const [boardErrors, setBoardErrors] = useState<FieldErrorMap>({});
  const me = useMe({ enabled: hasToken });

  const requested = isStepId(stepParam) ? stepParam : undefined;
  const isCheckingProfile = hasToken && me.isPending;
  const profileError = hasToken && me.isError ? me.error : null;
  const first =
    isCheckingProfile || profileError !== null
      ? null
      : firstIncompleteStep(hasToken, me.data);
  const current: StepId | null =
    first === null ? null : resolveStep(requested, first, draft !== null);

  // A dead token (401) means starting over at the account step with no stale session.
  useEffect(() => {
    if (
      profileError !== null &&
      isApiError(profileError) &&
      profileError.status === 401
    ) {
      tokenStore.clear();
      queryClient.removeQueries({ queryKey: meQueryKey });
      setHasToken(false);
    }
  }, [profileError, queryClient]);

  useEffect(() => {
    if (isCheckingProfile || profileError !== null) return;
    if (first === null) {
      void navigate('/explore', { replace: true });
      return;
    }
    if (current !== null && current !== requested) {
      void navigate(`/onboarding/${current}`, { replace: true });
    }
  }, [current, first, isCheckingProfile, navigate, profileError, requested]);

  const renderStep = (): React.ReactNode => {
    if (profileError !== null) {
      return (
        <div className="sheet onboarding__form">
          <ApiErrorBanner
            error={profileError}
            title="Your setup couldn't be checked."
            onRetry={() => void me.refetch()}
          />
        </div>
      );
    }
    if (current === null) return <ProfileSkeleton />;
    switch (current) {
      case 'account':
        return <AccountStep onAuthenticated={() => setHasToken(true)} />;
      case 'consent':
        return (
          <ConsentStep onRecorded={() => void navigate('/onboarding/board')} />
        );
      case 'board':
        return (
          <BoardStep
            draft={draft}
            serverErrors={boardErrors}
            onContinue={(next) => {
              setDraft(next);
              setBoardErrors({});
              void navigate('/onboarding/goals');
            }}
          />
        );
      case 'goals':
        return draft === null ? (
          <ProfileSkeleton />
        ) : (
          <GoalsStep
            draft={draft}
            onSaved={() => void navigate('/explore', { replace: true })}
            onBoardFieldErrors={(errors) => {
              setBoardErrors(errors);
              void navigate('/onboarding/board');
            }}
            onBack={() => void navigate('/onboarding/board')}
          />
        );
    }
  };

  return (
    <article className="page onboarding">
      <div className="onboarding__header">
        <p className="page__eyebrow">First run</p>
        <h1>Set up Beacon Climb</h1>
        <p className="page__lede">
          A pseudonymous account, a clear choice about logging, and the board
          you climb on. Nothing about your body is asked.
        </p>
      </div>

      <OnboardingProgress current={current ?? 'account'} />

      <div className="onboarding__columns">
        {renderStep()}
        {current === 'account' ? (
          <aside className="onboarding__aside" aria-label="Consent document">
            <ConsentDocumentPanel />
          </aside>
        ) : null}
      </div>
    </article>
  );
};
