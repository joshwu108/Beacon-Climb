import { useQueryClient } from '@tanstack/react-query';
import { Redirect, router } from 'expo-router';
import { useEffect, useState } from 'react';
import { isApiError } from '../api/errors';
import { tokenStore } from '../auth/token-store';
import { ApiErrorBanner } from '../components/ui/ApiErrorBanner';
import { Screen } from '../components/ui/Screen';
import { Skeleton } from '../components/ui/Skeleton';
import { meQueryKey, useMe } from '../features/auth/useMe';
import { useHasToken } from '../features/auth/useSession';
import { Progress } from '../features/onboarding/Progress';
import { AccountStep } from '../features/onboarding/steps/AccountStep';
import {
  BoardStep,
  type BoardDraft,
} from '../features/onboarding/steps/BoardStep';
import { ConsentStep } from '../features/onboarding/steps/ConsentStep';
import { GoalsStep } from '../features/onboarding/steps/GoalsStep';
import {
  ONBOARDING_STEPS,
  firstIncompleteStep,
  type StepId,
} from '../features/onboarding/steps';

/**
 * First run: account -> consent -> board and angle -> level and goals. Completed steps are
 * skipped on resume (stored token + GET /v1/me), as in the web app; a dead token starts over.
 */
export default function Onboarding() {
  const queryClient = useQueryClient();
  const hasToken = useHasToken();
  const me = useMe(hasToken);
  const [draft, setDraft] = useState<BoardDraft | null>(null);
  const [onGoals, setOnGoals] = useState(false);
  const [boardErrors, setBoardErrors] = useState<{
    board_id?: string;
    angle_deg?: string;
  }>({});

  const isDeadToken = isApiError(me.error) && me.error.status === 401;
  useEffect(() => {
    if (isDeadToken) {
      tokenStore.clear();
      queryClient.removeQueries({ queryKey: meQueryKey });
    }
  }, [isDeadToken, queryClient]);

  const checking = hasToken && me.isPending;
  const first = checking ? null : firstIncompleteStep(hasToken, me.data);
  const current: StepId | null =
    first === 'board' && onGoals && draft !== null ? 'goals' : first;

  if (hasToken && !checking && !me.isError && first === null)
    return <Redirect href="/session" />;

  const step = ONBOARDING_STEPS.find((s) => s.id === current);
  return (
    <Screen
      eyebrow="Set up Beacon Climb"
      title={step?.title ?? 'Checking your setup'}
      testID={current ? `onboarding-${current}` : 'onboarding'}
    >
      {current ? <Progress current={current} /> : null}
      {me.isError && !isDeadToken ? (
        <ApiErrorBanner
          error={me.error}
          title="Your setup couldn't be checked."
          onRetry={() => void me.refetch()}
        />
      ) : null}
      {current === null && !me.isError ? (
        <Skeleton label="Checking your setup" />
      ) : null}
      {current === 'account' ? (
        <AccountStep onAuthenticated={() => undefined} />
      ) : null}
      {current === 'consent' ? (
        <ConsentStep onRecorded={() => undefined} />
      ) : null}
      {current === 'board' ? (
        <BoardStep
          draft={draft}
          serverErrors={boardErrors}
          onContinue={(next) => {
            setDraft(next);
            setBoardErrors({});
            setOnGoals(true);
          }}
        />
      ) : null}
      {current === 'goals' && draft !== null ? (
        <GoalsStep
          draft={draft}
          onSaved={() => router.replace('/session')}
          onBoardFieldErrors={(errors) => {
            setBoardErrors(errors);
            setOnGoals(false);
          }}
          onBack={() => setOnGoals(false)}
        />
      ) : null}
    </Screen>
  );
}
