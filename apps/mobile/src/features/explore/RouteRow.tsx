import { formatObservations, isLowEvidence } from '@web/features/explore/grade';
import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { AppText } from '../../components/ui/AppText';
import { GradeMark } from '../../components/ui/GradeMark';
import { Stamp } from '../../components/ui/Stamp';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, HIT, SPACE } from '../../theme/theme';
import type { RouteVariantSummary } from './useRoutes';

/** One index line, guidebook style: grade numeral in the margin, name, evidence. */
export const RouteRow = ({
  route,
  trailing,
  testID,
}: {
  route: RouteVariantSummary;
  trailing?: string;
  testID?: string;
}) => {
  const { color } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${route.name}, ${route.grade_numeric === null ? 'ungraded' : `grade ${route.grade_numeric}`}, ${formatObservations(route.grade_observation_count)}${trailing ? `, ${trailing}` : ''}. Open route`}
      onPress={() =>
        router.push(`/route/${encodeURIComponent(route.route_variant_id)}`)
      }
      testID={testID}
      style={({ pressed }) => [
        styles.row,
        { borderBottomColor: color.borderSubtle },
        pressed && { backgroundColor: color.surfaceSunken },
      ]}
    >
      <GradeMark route={route} size="row" />
      <View style={styles.text}>
        <AppText variant="label" numberOfLines={2}>
          {route.name}
        </AppText>
        <View style={styles.meta}>
          <AppText variant="small" tone="muted">
            {formatObservations(route.grade_observation_count)}
          </AppText>
          {isLowEvidence(route) ? (
            <Stamp label="Low evidence" tone="warning" />
          ) : null}
        </View>
        {trailing ? (
          <AppText variant="small" tone="muted">
            {trailing}
          </AppText>
        ) : null}
      </View>
      <AppText variant="title" tone="muted" aria-hidden>
        ›
      </AppText>
    </Pressable>
  );
};

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.md,
    minHeight: HIT + 20,
    paddingVertical: SPACE.sm,
    borderBottomWidth: BORDER_WIDTH,
  },
  text: { flex: 1, gap: 2 },
  meta: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.xs,
    flexWrap: 'wrap',
  },
});
