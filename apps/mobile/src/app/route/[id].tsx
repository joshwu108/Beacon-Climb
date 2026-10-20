import { formatObservations, isLowEvidence } from '@web/features/explore/grade';
import { router, useLocalSearchParams } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { isApiError } from '../../api/errors';
import { BoardDiagram } from '../../components/board/BoardDiagram';
import { BoardLegend } from '../../components/board/BoardLegend';
import { BackBar } from '../../components/shell/BackBar';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { AppText } from '../../components/ui/AppText';
import { Banner } from '../../components/ui/Banner';
import { Button } from '../../components/ui/Button';
import { GradeMark } from '../../components/ui/GradeMark';
import { Screen } from '../../components/ui/Screen';
import { Sheet } from '../../components/ui/Sheet';
import { Skeleton } from '../../components/ui/Skeleton';
import { Stamp } from '../../components/ui/Stamp';
import { findBoard, useBoards } from '../../features/boards/useBoards';
import { RouteRow } from '../../features/explore/RouteRow';
import {
  describeDistance,
  useRoute,
  useSimilarRoutes,
} from '../../features/explore/useRoutes';
import { logAttemptHref } from '../../features/session/LineupHero';
import { useTheme } from '../../theme/ThemeProvider';
import { SPACE } from '../../theme/theme';

const MoreLikeThis = ({ routeVariantId }: { routeVariantId: string }) => {
  const similar = useSimilarRoutes(routeVariantId);
  return (
    <View style={styles.section} testID="more-like-this">
      <AppText variant="title" accessibilityRole="header">
        More like this
      </AppText>
      {similar.isPending ? <Skeleton label="Finding similar routes" /> : null}
      {similar.isError ? (
        <ApiErrorBanner
          error={similar.error}
          title="Similar routes couldn't be loaded."
          onRetry={() => void similar.refetch()}
        />
      ) : null}
      {similar.data ? (
        similar.data.model_version === null ||
        similar.data.items.length === 0 ? (
          <Banner title="No similar routes yet.">
            There's no layout embedding for this route, so nothing can be
            compared.
          </Banner>
        ) : (
          <>
            <AppText variant="small" tone="muted">
              Same board and angle, closest hold layout first. A model's layout
              comparison ({similar.data.model_version}), not advice.
            </AppText>
            {similar.data.items.map((item) => (
              <RouteRow
                key={item.route.route_variant_id}
                route={item.route}
                trailing={`${describeDistance(item.distance)} · distance ${item.distance.toFixed(2)}`}
                testID={`similar-${item.route.route_variant_id}`}
              />
            ))}
          </>
        )
      ) : null}
    </View>
  );
};

/** Route detail: board with this route's holds, grade and evidence, tags, then "More like this". */
export default function RouteDetail() {
  const { color } = useTheme();
  const { id = '' } = useLocalSearchParams<{ id: string }>();
  const routeVariantId = decodeURIComponent(id);
  const route = useRoute(routeVariantId);
  const boards = useBoards();

  if (route.isError) {
    const notFound = isApiError(route.error) && route.error.status === 404;
    return (
      <View style={[styles.fill, { backgroundColor: color.surface }]}>
        <BackBar fallback="/explore" />
        <Screen
          underHeader
          eyebrow="Route"
          title={
            notFound
              ? "This route isn't available."
              : "This route couldn't be loaded."
          }
        >
          {notFound ? (
            <AppText tone="muted">
              It may have been retired, or it's not on your board.
            </AppText>
          ) : (
            <ApiErrorBanner
              error={route.error}
              onRetry={() => void route.refetch()}
            />
          )}
        </Screen>
      </View>
    );
  }

  const variant = route.data;
  const board = variant
    ? findBoard(boards.data?.items, variant.board_id)
    : null;

  return (
    <View
      style={[styles.fill, { backgroundColor: color.surface }]}
      testID="route-detail"
    >
      <BackBar
        fallback="/explore"
        context={
          variant ? `${variant.angle_deg}° · ${variant.board_id}` : undefined
        }
      />
      <Screen
        underHeader
        eyebrow="Route"
        title={variant?.name ?? 'Loading route'}
        aside={variant ? <GradeMark route={variant} /> : undefined}
      >
        {variant === undefined ? <Skeleton label="Loading route" /> : null}
        {variant ? (
          <>
            {board ? (
              <Sheet elevation="lifted" style={styles.boardSheet}>
                <BoardDiagram
                  board={board}
                  placements={variant.placements}
                  routeName={variant.name}
                  testID="route-board"
                />
                <BoardLegend />
              </Sheet>
            ) : boards.isError ? (
              <ApiErrorBanner
                error={boards.error}
                title="The board couldn't be drawn."
                onRetry={() => void boards.refetch()}
              />
            ) : boards.isPending ? (
              <Skeleton label="Drawing the board" />
            ) : (
              <Banner
                tone="warning"
                title={`Board ${variant.board_id} isn't in the catalog.`}
              />
            )}
            <Button
              label="Log an attempt"
              accessibilityLabel={`Log an attempt on ${variant.name}`}
              onPress={() =>
                router.push(logAttemptHref(variant.route_variant_id))
              }
              testID="route-log"
            />
            <View style={styles.facts}>
              <View style={styles.fact}>
                <AppText variant="stamp" tone="muted">
                  Evidence
                </AppText>
                <View style={styles.inline}>
                  <AppText>
                    {formatObservations(variant.grade_observation_count)}
                  </AppText>
                  {isLowEvidence(variant) ? (
                    <Stamp label="Low evidence" tone="warning" />
                  ) : null}
                </View>
                {variant.grade_observation_count === 0 ? (
                  <AppText variant="small" tone="muted">
                    No one has logged a grade for this route yet.
                  </AppText>
                ) : null}
              </View>
              <View style={styles.fact}>
                <AppText variant="stamp" tone="muted">
                  Style tags
                </AppText>
                {variant.missing_tags || variant.tags.length === 0 ? (
                  <AppText tone="muted">Not available for this route.</AppText>
                ) : (
                  <View style={styles.inline}>
                    {variant.tags.map((tag) => (
                      <Stamp key={tag} label={tag} />
                    ))}
                  </View>
                )}
              </View>
              <View style={styles.fact}>
                <AppText variant="stamp" tone="muted">
                  Holds
                </AppText>
                <AppText>
                  {variant.placements.length} placement
                  {variant.placements.length === 1 ? '' : 's'}
                </AppText>
              </View>
            </View>
            <MoreLikeThis routeVariantId={variant.route_variant_id} />
          </>
        ) : null}
      </Screen>
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  boardSheet: { padding: SPACE.sm },
  facts: { gap: SPACE.md },
  fact: { gap: SPACE.xxs },
  inline: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.xs,
    flexWrap: 'wrap',
  },
  section: { gap: SPACE.xs, marginTop: SPACE.lg },
});
