import { describeReasonCode } from '@web/features/decision/reason-codes';
import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { BoardDiagram } from '../../components/board/BoardDiagram';
import { AppText } from '../../components/ui/AppText';
import { Button } from '../../components/ui/Button';
import { GradeMark } from '../../components/ui/GradeMark';
import { Stamp } from '../../components/ui/Stamp';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, RADIUS, SPACE } from '../../theme/theme';
import { useRoute } from '../explore/useRoutes';
import { logAttemptHref, type LineupCardProps } from './LineupHero';

/** Later picks: a thumbnail board, position, name, first reason, grade; tap to open. */
export const LineupRow = ({
  item,
  board,
  isNew,
  isBusy,
  onSwap,
  onDismiss,
}: LineupCardProps) => {
  const { color } = useTheme();
  const route = useRoute(item.route_variant_id);
  const firstReason = item.reason_codes[0];
  return (
    <View
      style={[
        styles.row,
        {
          borderColor: color.borderSubtle,
          backgroundColor: color.surfaceRaised,
        },
      ]}
      testID={`lineup-row-${item.position}`}
    >
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`Position ${item.position}: ${item.route.name}. Open route`}
        onPress={() =>
          router.push(`/route/${encodeURIComponent(item.route_variant_id)}`)
        }
        style={({ pressed }) => [styles.main, pressed && { opacity: 0.7 }]}
      >
        <View style={styles.thumb}>
          {board !== null && route.data ? (
            <BoardDiagram
              board={board}
              placements={route.data.placements}
              thumbnail
            />
          ) : (
            <View
              style={[
                styles.thumbPlaceholder,
                { backgroundColor: color.surfaceSunken },
              ]}
            />
          )}
        </View>
        <View style={styles.text}>
          <View style={styles.stamps}>
            <AppText variant="stamp" tone="muted">
              {String(item.position).padStart(2, '0')}
            </AppText>
            {isNew ? <Stamp label="New" tone="accent" /> : null}
          </View>
          <AppText variant="label" numberOfLines={2}>
            {item.route.name}
          </AppText>
          {firstReason ? (
            <AppText variant="small" tone="muted" numberOfLines={1}>
              {describeReasonCode(firstReason).label}
              {item.reason_codes.length > 1
                ? ` +${item.reason_codes.length - 1}`
                : ''}
            </AppText>
          ) : null}
        </View>
        <GradeMark route={item.route} size="row" />
      </Pressable>
      <View style={styles.actions}>
        <Button
          label="Log"
          compact
          accessibilityLabel={`Log an attempt on ${item.route.name}`}
          onPress={() => router.push(logAttemptHref(item.route_variant_id))}
        />
        <Button
          label="Swap"
          variant="secondary"
          compact
          accessibilityLabel={`Swap ${item.route.name}`}
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
    </View>
  );
};

const styles = StyleSheet.create({
  row: {
    borderWidth: BORDER_WIDTH,
    borderRadius: RADIUS.sheet,
    padding: SPACE.sm,
    gap: SPACE.sm,
  },
  main: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.sm,
    minHeight: 64,
  },
  thumb: { width: 56 },
  thumbPlaceholder: { width: 56, height: 64, borderRadius: RADIUS.input },
  text: { flex: 1, gap: 2 },
  stamps: { flexDirection: 'row', gap: SPACE.xs, alignItems: 'center' },
  actions: { flexDirection: 'row', gap: SPACE.xs },
});
