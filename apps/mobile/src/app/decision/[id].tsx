import {
  describeModelVersion,
  formatRankScore,
} from '@web/features/decision/reason-codes';
import { formatObservations } from '@web/features/explore/grade';
import { goalLabel } from '@web/features/onboarding/goals';
import { formatDateTime } from '@web/lib/format';
import { router, useLocalSearchParams } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { isApiError } from '../../api/errors';
import { BackBar } from '../../components/shell/BackBar';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { AppText } from '../../components/ui/AppText';
import { Button } from '../../components/ui/Button';
import { GradeMark } from '../../components/ui/GradeMark';
import { Screen } from '../../components/ui/Screen';
import { Sheet } from '../../components/ui/Sheet';
import { Skeleton } from '../../components/ui/Skeleton';
import {
  ColdStartNotice,
  EstimateLine,
  FallbackNotice,
  ReasonList,
} from '../../features/decision/ReasonList';
import { byPosition, useDecision } from '../../features/decision/useDecision';
import { logAttemptHref } from '../../features/session/LineupHero';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, SPACE } from '../../theme/theme';

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const Fact = ({ term, children }: { term: string; children: string }) => (
  <View
    style={styles.fact}
    accessible
    accessibilityLabel={`${term}: ${children}`}
  >
    <AppText variant="stamp" tone="muted">
      {term}
    </AppText>
    <AppText variant="small" selectable>
      {children}
    </AppText>
  </View>
);

/**
 * The decision record: what was asked, which policy and model answered, the flags, and
 * every ranked route with all of its reasons. Read-only; a decision never changes.
 */
export default function DecisionRecord() {
  const { color } = useTheme();
  const { id = '', position } = useLocalSearchParams<{
    id: string;
    position?: string;
  }>();
  const valid = UUID_RE.test(id);
  const decision = useDecision(id, valid);
  const highlighted = Number(position);

  if (!valid || decision.isError) {
    const notFound =
      !valid || (isApiError(decision.error) && decision.error.status === 404);
    return (
      <View style={[styles.fill, { backgroundColor: color.surface }]}>
        <BackBar />
        <Screen
          underHeader
          eyebrow="Decision record"
          title={
            notFound
              ? "We couldn't find this decision."
              : "This decision couldn't be loaded."
          }
        >
          {notFound ? (
            <AppText tone="muted">
              The link may be old, or it isn't yours.
            </AppText>
          ) : (
            <ApiErrorBanner
              error={decision.error}
              onRetry={() => void decision.refetch()}
            />
          )}
        </Screen>
      </View>
    );
  }

  const data = decision.data;
  return (
    <View
      style={[styles.fill, { backgroundColor: color.surface }]}
      testID="decision-record"
    >
      <BackBar context="Decision record" />
      <Screen underHeader eyebrow="Decision record" title="Why these routes">
        {data === undefined ? <Skeleton label="Loading this decision" /> : null}
        {data ? (
          <>
            <AppText tone="muted">
              Recorded {formatDateTime(data.created_at)}. Nothing here estimates
              your chance of sending.
            </AppText>
            {data.cold_start ? <ColdStartNotice /> : null}
            {data.fallback_used ? <FallbackNotice /> : null}
            {byPosition(data.recommendations).map((item) => {
              const current = item.position === highlighted;
              return (
                <Sheet
                  key={item.route_variant_id}
                  elevation={current ? 'lifted' : 'sheet'}
                  style={
                    current
                      ? { borderColor: color.accent, borderWidth: 2 }
                      : undefined
                  }
                >
                  <View style={styles.head}>
                    <View style={styles.headText}>
                      <AppText
                        variant="stamp"
                        tone={current ? 'accent' : 'muted'}
                      >
                        Position {item.position}
                      </AppText>
                      <AppText variant="title">{item.route.name}</AppText>
                      <AppText variant="small" tone="muted">
                        {formatObservations(item.route.grade_observation_count)}
                      </AppText>
                    </View>
                    <GradeMark route={item.route} size="row" />
                  </View>
                  <ReasonList codes={item.reason_codes} detailed />
                  <AppText variant="small" tone="muted">
                    Ordering value {formatRankScore(item.rank_score)} (relative
                    order only, not a chance of success)
                  </AppText>
                  <EstimateLine
                    probability={item.predicted_send_probability}
                    uncertaintyLabel={item.uncertainty_label}
                  />
                  <View style={styles.actions}>
                    <Button
                      label="Log attempt"
                      compact
                      onPress={() =>
                        router.push(logAttemptHref(item.route_variant_id))
                      }
                      accessibilityLabel={`Log an attempt on ${item.route.name}`}
                    />
                    <Button
                      label="Open route"
                      variant="secondary"
                      compact
                      onPress={() =>
                        router.push(
                          `/route/${encodeURIComponent(item.route_variant_id)}`,
                        )
                      }
                      accessibilityLabel={`Open ${item.route.name}`}
                    />
                  </View>
                </Sheet>
              );
            })}
            <View style={[styles.about, { borderTopColor: color.border }]}>
              <AppText variant="title" accessibilityRole="header">
                About this list
              </AppText>
              <Fact term="Asked for">{`${goalLabel(data.request.goal)}, up to ${data.request.max_routes} routes`}</Fact>
              <Fact term="Board and angle">{`${data.request.board_id} at ${data.request.angle_deg}°`}</Fact>
              <Fact term="Policy">{data.policy_version}</Fact>
              <Fact term="Model">
                {describeModelVersion(data.model_version)}
              </Fact>
              <Fact term="History used up to">
                {formatDateTime(data.feature_as_of)}
              </Fact>
              <Fact term="Features">
                {data.features_source === 'STREAM'
                  ? 'from the live stream'
                  : 'recomputed on request'}
              </Fact>
              <Fact term="Flags">{`${data.cold_start ? 'cold start' : 'history-based'}; ${data.fallback_used ? 'fallback used' : 'full ranking'}`}</Fact>
              <Fact term="Left out on request">
                {data.request.exclude_route_variant_ids.length === 0
                  ? 'none'
                  : data.request.exclude_route_variant_ids.join(', ')}
              </Fact>
              <Fact term="Decision id">{data.decision_id}</Fact>
            </View>
          </>
        ) : null}
      </Screen>
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  head: { flexDirection: 'row', gap: SPACE.sm, alignItems: 'flex-start' },
  headText: { flex: 1, gap: 2 },
  actions: { flexDirection: 'row', gap: SPACE.xs, flexWrap: 'wrap' },
  about: {
    gap: SPACE.sm,
    borderTopWidth: BORDER_WIDTH,
    paddingTop: SPACE.lg,
    marginTop: SPACE.md,
  },
  fact: { gap: 2 },
});
