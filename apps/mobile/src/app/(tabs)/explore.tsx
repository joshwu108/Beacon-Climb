import {
  hasGradeFilter,
  toRoutesQuery,
  validateFilters,
  type ExploreFilters,
} from '@web/features/explore/filters';
import { useState } from 'react';
import { ActivityIndicator, FlatList, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { AppText } from '../../components/ui/AppText';
import { Banner } from '../../components/ui/Banner';
import { Button } from '../../components/ui/Button';
import { Chips } from '../../components/ui/Chips';
import { Skeleton } from '../../components/ui/Skeleton';
import { Stepper } from '../../components/ui/Stepper';
import { useMe } from '../../features/auth/useMe';
import { usePreferredBoard } from '../../features/boards/useBoards';
import { RouteRow } from '../../features/explore/RouteRow';
import { useRoutes } from '../../features/explore/useRoutes';
import { useTheme } from '../../theme/ThemeProvider';
import { SPACE } from '../../theme/theme';

const GRADE_FLOOR = 0;
const GRADE_CEILING = 40;

/**
 * Explore: every route on the climber's board at an angle, grade ascending (ungraded
 * last), paged by cursor. Angle chips come from the board's available angles; the grade
 * range is optional and validated before a request is built (apps/web's filters.ts).
 */
export default function ExploreTab() {
  const { color } = useTheme();
  const insets = useSafeAreaInsets();
  const me = useMe();
  const preferences = me.data?.preferences ?? null;
  const { board } = usePreferredBoard(preferences?.board_id);
  const [filters, setFilters] = useState<ExploreFilters | null>(null);
  const [showGrades, setShowGrades] = useState(false);

  const effective: ExploreFilters | null =
    filters ??
    (preferences
      ? { angleDeg: preferences.angle_deg, gradeMin: null, gradeMax: null }
      : null);
  const filterError = effective ? validateFilters(effective) : null;
  const query =
    preferences && effective && filterError === null
      ? toRoutesQuery(preferences.board_id, effective)
      : null;
  const routes = useRoutes(query);
  const items = routes.data?.pages.flatMap((page) => page.items) ?? [];

  const update = (patch: Partial<ExploreFilters>) =>
    effective && setFilters({ ...effective, ...patch });

  const header = (
    <View style={[styles.header, { paddingTop: insets.top + SPACE.lg }]}>
      <AppText variant="stamp" tone="muted">
        Index
      </AppText>
      <AppText variant="screen" accessibilityRole="header">
        Explore routes
      </AppText>
      <AppText tone="muted">
        Every route on your board at one angle, easiest first.
      </AppText>
      {board && effective ? (
        <Chips
          label="Angle"
          options={board.available_angles.map((a) => ({
            value: a,
            label: `${a}°`,
            accessibilityLabel: `${a} degrees`,
          }))}
          value={effective.angleDeg}
          onChange={(angleDeg) => update({ angleDeg })}
          testIDPrefix="explore-angle"
          scroll
        />
      ) : null}
      {effective ? (
        showGrades ? (
          <View style={styles.grades}>
            <Stepper
              label="Grade from"
              value={effective.gradeMin}
              min={GRADE_FLOOR}
              max={GRADE_CEILING}
              emptyText="any"
              onChange={(gradeMin) => update({ gradeMin })}
            />
            <Stepper
              label="Grade to"
              value={effective.gradeMax}
              min={GRADE_FLOOR}
              max={GRADE_CEILING}
              emptyText="any"
              onChange={(gradeMax) => update({ gradeMax })}
            />
            <Button
              label="Clear grade range"
              variant="quiet"
              compact
              onPress={() => {
                update({ gradeMin: null, gradeMax: null });
                setShowGrades(false);
              }}
              style={styles.start}
            />
          </View>
        ) : (
          <Button
            label={
              hasGradeFilter(effective) ? 'Edit grade range' : 'Filter by grade'
            }
            variant="secondary"
            compact
            onPress={() => setShowGrades(true)}
            style={styles.start}
          />
        )
      ) : null}
      {filterError ? <Banner tone="error" title={filterError} /> : null}
      {routes.isError ? (
        <ApiErrorBanner
          error={routes.error}
          title="Routes couldn't be loaded."
          onRetry={() => void routes.refetch()}
        />
      ) : null}
      {me.isPending || routes.isPending ? (
        <Skeleton label="Loading routes" />
      ) : null}
    </View>
  );

  return (
    <FlatList
      testID="explore-list"
      style={{ backgroundColor: color.surface }}
      contentContainerStyle={styles.content}
      data={items}
      keyExtractor={(item) => item.route_variant_id}
      ListHeaderComponent={header}
      renderItem={({ item }) => (
        <RouteRow route={item} testID={`route-${item.route_variant_id}`} />
      )}
      onEndReachedThreshold={0.4}
      onEndReached={() => {
        if (routes.hasNextPage && !routes.isFetchingNextPage)
          void routes.fetchNextPage();
      }}
      ListEmptyComponent={
        routes.isSuccess ? (
          <Banner title="No routes match.">
            {effective && hasGradeFilter(effective)
              ? 'Widen the grade range or pick another angle.'
              : 'No routes are set at this angle yet.'}
          </Banner>
        ) : undefined
      }
      ListFooterComponent={
        routes.isFetchingNextPage ? (
          <ActivityIndicator
            accessibilityLabel="Loading more routes"
            color={color.text}
            style={styles.more}
          />
        ) : routes.hasNextPage ? (
          <Button
            label="Load more"
            variant="secondary"
            onPress={() => void routes.fetchNextPage()}
            style={styles.more}
          />
        ) : items.length > 0 ? (
          <AppText variant="small" tone="muted" style={styles.more}>
            End of the index · {items.length} routes
          </AppText>
        ) : undefined
      }
    />
  );
}

const styles = StyleSheet.create({
  content: { paddingHorizontal: SPACE.gutter, paddingBottom: SPACE.xxl },
  header: { gap: SPACE.sm, marginBottom: SPACE.md },
  grades: { gap: SPACE.sm },
  start: { alignSelf: 'flex-start' },
  more: { marginTop: SPACE.lg, alignSelf: 'center' },
});
