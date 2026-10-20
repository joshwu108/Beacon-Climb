import { formatObservations } from '@web/features/explore/grade';
import { router } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { BoardDiagram } from '../../components/board/BoardDiagram';
import { AppText } from '../../components/ui/AppText';
import { Button } from '../../components/ui/Button';
import { GradeMark } from '../../components/ui/GradeMark';
import { Sheet } from '../../components/ui/Sheet';
import { Skeleton } from '../../components/ui/Skeleton';
import { Stamp } from '../../components/ui/Stamp';
import { SPACE } from '../../theme/theme';
import type { Board } from '../boards/useBoards';
import { EstimateLine, ReasonList } from '../decision/ReasonList';
import type { Recommendation } from '../decision/useDecision';
import { useRoute } from '../explore/useRoutes';

export interface LineupCardProps {
  readonly item: Recommendation;
  readonly decisionId: string;
  readonly board: Board | null;
  readonly isNew: boolean;
  readonly isBusy: boolean;
  readonly onSwap: () => void;
  readonly onDismiss: () => void;
}

export const logAttemptHref = (routeVariantId: string) =>
  `/log-attempt?route_variant_id=${encodeURIComponent(routeVariantId)}` as const;

/**
 * "Next up": the first pick, on a lifted sheet. The board is the hero (with the teal
 * recommended rays), the grade sits beside the name like a guidebook entry, and the "why"
 * is the decision record's reason codes with their explanations.
 */
export const LineupHero = ({
  item,
  decisionId,
  board,
  isNew,
  isBusy,
  onSwap,
  onDismiss,
}: LineupCardProps) => {
  const route = useRoute(item.route_variant_id);
  return (
    <Sheet elevation="lifted" testID="lineup-hero" style={styles.sheet}>
      <View style={styles.stamps}>
        <Stamp label="Recommended" tone="accent" />
        <AppText variant="stamp" tone="muted">
          Next up · {String(item.position).padStart(2, '0')}
        </AppText>
        {isNew ? <Stamp label="New" tone="accent" /> : null}
      </View>
      <View style={styles.titleRow}>
        <View style={styles.titleText}>
          <AppText
            variant="title"
            accessibilityRole="header"
            testID="hero-name"
          >
            {item.route.name}
          </AppText>
          <AppText variant="small" tone="muted">
            {formatObservations(item.route.grade_observation_count)}
          </AppText>
        </View>
        <GradeMark route={item.route} />
      </View>
      {board !== null && route.data ? (
        <BoardDiagram
          board={board}
          placements={route.data.placements}
          routeName={item.route.name}
          recommended
          testID="hero-board"
        />
      ) : route.isError ? (
        <AppText variant="small" tone="muted">
          The holds for this route couldn't be loaded.
        </AppText>
      ) : (
        <Skeleton label="Drawing the board" widths={['100%', '100%', '70%']} />
      )}
      <View style={styles.why}>
        <AppText variant="stamp" tone="accent">
          Why this route
        </AppText>
        <ReasonList codes={item.reason_codes} detailed />
        <EstimateLine
          probability={item.predicted_send_probability}
          uncertaintyLabel={item.uncertainty_label}
        />
      </View>
      <Button
        label="Log attempt"
        accessibilityLabel={`Log an attempt on ${item.route.name}`}
        onPress={() => router.push(logAttemptHref(item.route_variant_id))}
        testID="hero-log"
      />
      <View style={styles.secondary}>
        <Button
          label="Route"
          variant="secondary"
          compact
          accessibilityLabel={`Open ${item.route.name}`}
          onPress={() =>
            router.push(`/route/${encodeURIComponent(item.route_variant_id)}`)
          }
          testID="hero-open"
        />
        <Button
          label="Swap"
          variant="secondary"
          compact
          accessibilityLabel={`Swap ${item.route.name} for another route`}
          onPress={onSwap}
          disabled={isBusy}
        />
        <Button
          label="Dismiss"
          variant="quiet"
          compact
          accessibilityLabel={`Dismiss ${item.route.name}`}
          onPress={onDismiss}
          disabled={isBusy}
        />
      </View>
      <Button
        label="Full decision record"
        variant="quiet"
        compact
        onPress={() =>
          router.push(`/decision/${decisionId}?position=${item.position}`)
        }
        testID="hero-decision"
        style={styles.record}
      />
    </Sheet>
  );
};

const styles = StyleSheet.create({
  sheet: { gap: SPACE.md, padding: SPACE.md },
  stamps: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.xs,
    flexWrap: 'wrap',
  },
  titleRow: { flexDirection: 'row', alignItems: 'flex-start', gap: SPACE.sm },
  titleText: { flex: 1, gap: SPACE.xxs },
  why: { gap: SPACE.xs },
  secondary: { flexDirection: 'row', gap: SPACE.xs, flexWrap: 'wrap' },
  record: { alignSelf: 'flex-start' },
});
