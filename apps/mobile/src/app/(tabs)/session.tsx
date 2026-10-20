import { useQueryClient } from '@tanstack/react-query';
import { fieldErrorsFrom } from '@web/components/ui/field-errors';
import { GOALS } from '@web/features/onboarding/goals';
import {
  isGoal,
  MAX_ROUTES,
  MIN_ROUTES,
} from '@web/features/session/session-params';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { isApiError } from '../../api/errors';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { AppText } from '../../components/ui/AppText';
import { Banner } from '../../components/ui/Banner';
import { Button } from '../../components/ui/Button';
import { Chips } from '../../components/ui/Chips';
import { Reveal } from '../../components/ui/Reveal';
import { Screen } from '../../components/ui/Screen';
import { Sheet } from '../../components/ui/Sheet';
import { Skeleton } from '../../components/ui/Skeleton';
import { Stamp } from '../../components/ui/Stamp';
import { Stepper } from '../../components/ui/Stepper';
import { signOut } from '../../features/auth/useAuthenticate';
import { useMe } from '../../features/auth/useMe';
import { usePreferredBoard } from '../../features/boards/useBoards';
import {
  ColdStartNotice,
  FallbackNotice,
} from '../../features/decision/ReasonList';
import {
  byPosition,
  type Recommendation,
} from '../../features/decision/useDecision';
import { Freshness } from '../../features/session/Freshness';
import { LineupHero } from '../../features/session/LineupHero';
import { LineupRow } from '../../features/session/LineupRow';
import {
  POLICY_VERSIONS,
  sessionStore,
  useSessionState,
  withLineup,
  type Goal,
  type RecommendationResponse,
} from '../../features/session/session-store';
import {
  toRecommendationRequest,
  useRecommend,
} from '../../features/session/useRecommend';
import { SPACE } from '../../theme/theme';

/** The policy toggle is a debug tool: dev builds, or EXPO_PUBLIC_DEBUG=true. */
const SHOW_DEBUG = __DEV__ || process.env.EXPO_PUBLIC_DEBUG === 'true';

export default function SessionTab() {
  const queryClient = useQueryClient();
  const me = useMe();
  const preferences = me.data?.preferences ?? null;
  const { board } = usePreferredBoard(preferences?.board_id);
  const state = useSessionState((s) => s);
  const recommend = useRecommend();
  const [announcement, setAnnouncement] = useState('');

  const goal: Goal =
    state.goal ?? preferences?.goals.find(isGoal) ?? 'TECHNIQUE';

  const request = useCallback(
    (
      maxRoutes: number,
      excluded: readonly string[],
      announce: (response: RecommendationResponse) => string,
    ) => {
      if (preferences === null || recommend.isPending) return;
      const current = sessionStore.getState();
      const body = toRecommendationRequest({
        boardId: preferences.board_id,
        angleDeg: preferences.angle_deg,
        goal: current.goal ?? preferences.goals.find(isGoal) ?? 'TECHNIQUE',
        maxRoutes,
        sessionId: current.sessionId,
        excluded,
        policy: current.policy,
      });
      recommend.mutate(body, {
        onSuccess: (response) => {
          sessionStore.setState((s) => withLineup(s, response, excluded));
          setAnnouncement(announce(response));
        },
      });
    },
    [preferences, recommend],
  );

  // After an attempt is logged from a pick, rebuild once when this tab is back in focus.
  useFocusEffect(
    useCallback(() => {
      const current = sessionStore.getState();
      if (current.refreshReason === null || current.lineup === null) return;
      const reason = current.refreshReason;
      request(
        current.lineup.recommendations.length,
        current.excluded,
        (r) => `${reason}. ${r.recommendations.length} picks refreshed.`,
      );
    }, [request]),
  );

  if (me.isPending) {
    return (
      <Screen eyebrow="Plan" title="Build a session">
        <Skeleton label="Loading your board" />
      </Screen>
    );
  }
  if (me.isError || preferences === null) {
    return (
      <Screen eyebrow="Plan" title="Build a session">
        {me.isError ? (
          <ApiErrorBanner
            error={me.error}
            title="Your profile couldn't be loaded."
            onRetry={() => void me.refetch()}
          />
        ) : (
          <Banner
            tone="warning"
            title="Your board isn't set up yet."
            actionLabel="Finish setup"
            onAction={() => router.replace('/onboarding')}
          >
            Sessions are built for the board and angle in your setup.
          </Banner>
        )}
      </Screen>
    );
  }

  const lineup = state.lineup;
  const ranked = lineup ? byPosition(lineup.recommendations) : [];
  const count = lineup?.recommendations.length ?? state.routes;
  const build = () =>
    request(
      state.routes,
      state.excluded,
      (r) => `${r.recommendations.length} routes ready`,
    );
  const swap = (item: Recommendation) =>
    request(
      count,
      [...state.excluded, item.route_variant_id],
      () => `Replaced ${item.route.name}.`,
    );
  const dismiss = (item: Recommendation) =>
    request(
      Math.max(1, count - 1),
      [...state.excluded, item.route_variant_id],
      () => `Removed ${item.route.name}.`,
    );

  const error = recommend.error;
  const fieldErrors = fieldErrorsFrom(error, ['goal', 'max_routes']);
  const consentRequired =
    isApiError(error) && error.code === 'CONSENT_REQUIRED';
  const previous = new Set(state.previousIds);

  return (
    <Screen
      eyebrow="Plan"
      title="Build a session"
      testID="session-screen"
      aside={
        <View
          style={styles.chip}
          accessible
          accessibilityLabel={`Board ${preferences.board_id} at ${preferences.angle_deg} degrees`}
        >
          <AppText variant="grade" style={styles.angle}>
            {preferences.angle_deg}°
          </AppText>
          <AppText variant="stamp" tone="muted" numberOfLines={1}>
            {preferences.board_id}
          </AppText>
        </View>
      }
    >
      {board?.synthetic ? (
        <Stamp label="Synthetic demo board" tone="warning" />
      ) : null}

      <Sheet>
        <Chips
          label="What is this session for?"
          options={GOALS.map((g) => ({
            value: g.id,
            label: g.label,
            accessibilityLabel: `${g.label}: ${g.hint}`,
          }))}
          value={goal}
          onChange={(next) =>
            sessionStore.setState((s) => ({ ...s, goal: next }))
          }
          disabled={recommend.isPending}
          testIDPrefix="goal"
        />
        <AppText variant="small" tone="muted">
          {GOALS.find((g) => g.id === goal)?.hint}
        </AppText>
        <Stepper
          label="Routes in the session"
          value={state.routes}
          min={MIN_ROUTES}
          max={MAX_ROUTES}
          unit="routes"
          onChange={(routes) =>
            sessionStore.setState((s) => ({ ...s, routes }))
          }
          disabled={recommend.isPending}
          testID="routes"
        />
        {fieldErrors.goal || fieldErrors.max_routes ? (
          <AppText variant="small" tone="error">
            {fieldErrors.goal ?? fieldErrors.max_routes}
          </AppText>
        ) : null}
        {SHOW_DEBUG ? (
          <View style={styles.debug} testID="policy-debug">
            <Chips
              label="Debug · ranking policy"
              options={POLICY_VERSIONS.map((p) => ({
                value: p,
                label: p,
                accessibilityLabel: `Ranking policy ${p}${p === 'heuristic-v2' ? ', server default' : ''}`,
              }))}
              value={state.policy}
              onChange={(policy) =>
                sessionStore.setState((s) => ({ ...s, policy }))
              }
              disabled={recommend.isPending}
              testIDPrefix="policy"
            />
          </View>
        ) : null}
        <Button
          label={
            recommend.isPending
              ? 'Building'
              : lineup
                ? 'Refresh picks'
                : 'Build my session'
          }
          onPress={build}
          busy={recommend.isPending}
          testID="build-session"
        />
      </Sheet>

      <AppText
        accessibilityLiveRegion="polite"
        variant="small"
        tone="muted"
        style={styles.live}
        testID="session-status"
      >
        {recommend.isPending ? 'Building your session…' : announcement}
      </AppText>

      {consentRequired ? (
        <Banner
          tone="warning"
          title="Sessions need your consent to record decisions."
          actionLabel="Turn on logging"
          onAction={() => router.replace('/onboarding')}
        >
          Building a session records a decision, which needs logging to be on.
        </Banner>
      ) : error !== null && !fieldErrors.goal && !fieldErrors.max_routes ? (
        <ApiErrorBanner
          error={error}
          title="We couldn't build your session."
          onRetry={build}
        />
      ) : null}

      {lineup ? (
        <View style={styles.lineup} testID="lineup">
          <View style={styles.lineupHead}>
            <AppText variant="title" accessibilityRole="header">
              Your session · {ranked.length} route
              {ranked.length === 1 ? '' : 's'}
            </AppText>
            <Freshness
              featureAsOf={lineup.feature_as_of}
              source={lineup.features_source}
            />
            <AppText variant="small" tone="muted">
              Policy {lineup.policy_version}
              {state.excluded.length > 0
                ? ` · ${state.excluded.length} dismissed this session`
                : ''}
            </AppText>
            {state.excluded.length > 0 ? (
              <Button
                label="Bring dismissed routes back"
                variant="quiet"
                compact
                onPress={() =>
                  sessionStore.setState((s) => ({ ...s, excluded: [] }))
                }
                style={styles.start}
              />
            ) : null}
          </View>
          {lineup.cold_start ? <ColdStartNotice /> : null}
          {lineup.fallback_used ? <FallbackNotice /> : null}
          {ranked.length === 0 ? (
            <Banner
              title="No routes fit this goal."
              actionLabel="Browse all routes"
              onAction={() => router.navigate('/explore')}
            >
              Try another goal, or bring dismissed routes back.
            </Banner>
          ) : null}
          {ranked.map((item, index) => {
            const props = {
              item,
              decisionId: lineup.decision_id,
              board,
              isNew: previous.size > 0 && !previous.has(item.route_variant_id),
              isBusy: recommend.isPending,
              onSwap: () => swap(item),
              onDismiss: () => dismiss(item),
            };
            return (
              <Reveal
                key={item.route_variant_id}
                index={index}
                replayKey={lineup.decision_id}
              >
                {index === 0 ? (
                  <LineupHero {...props} />
                ) : (
                  <LineupRow {...props} />
                )}
              </Reveal>
            );
          })}
        </View>
      ) : recommend.isPending ? (
        <Skeleton
          label="Choosing routes"
          widths={['100%', '80%', '90%', '60%']}
        />
      ) : (
        <View style={styles.empty}>
          <AppText variant="title">Nothing planned yet.</AppText>
          <AppText tone="muted">
            Pick a goal and build a session. Each route comes with the reasons
            Beacon chose it. Nothing here predicts what you will do on the wall.
          </AppText>
        </View>
      )}

      <View style={styles.footer}>
        <AppText variant="small" tone="muted">
          Signed in as {me.data.username}
        </AppText>
        <Button
          label="Sign out"
          variant="quiet"
          compact
          onPress={() => {
            void signOut(queryClient).then(() => {
              sessionStore.reset();
              router.replace('/onboarding');
            });
          }}
          testID="sign-out"
        />
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  chip: { alignItems: 'flex-end', maxWidth: 140 },
  angle: { fontSize: 36, lineHeight: 40 },
  debug: { gap: SPACE.xs },
  live: { minHeight: 20 },
  lineup: { gap: SPACE.md },
  lineupHead: { gap: SPACE.xxs },
  start: { alignSelf: 'flex-start' },
  empty: { gap: SPACE.xs, paddingVertical: SPACE.lg },
  footer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: SPACE.xl,
  },
});
