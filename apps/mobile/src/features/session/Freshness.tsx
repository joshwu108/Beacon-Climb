import { formatClock, formatDateTime } from '@web/lib/format';
import { StyleSheet, View } from 'react-native';
import Svg, { Circle, Path } from 'react-native-svg';
import { AppText } from '../../components/ui/AppText';
import { useTheme } from '../../theme/ThemeProvider';
import { SPACE } from '../../theme/theme';
import type { RecommendationResponse } from './session-store';

type Source = RecommendationResponse['features_source'];

const SPOKEN: Record<Source, string> = {
  STREAM: 'from the live stream',
  SYNC: 'recomputed on request',
};

/** "features as of 14:02 · recomputed": a glyph and a word tell STREAM and SYNC apart. */
export const Freshness = ({
  featureAsOf,
  source,
}: {
  featureAsOf: string | null | undefined;
  source: Source;
}) => {
  const { color } = useTheme();
  const hasSnapshot = typeof featureAsOf === 'string' && featureAsOf !== '';
  const label = hasSnapshot
    ? `Features as of ${formatDateTime(featureAsOf)}, ${SPOKEN[source]}.`
    : `No attempts yet, so no feature snapshot; ${SPOKEN[source]}.`;
  return (
    <View style={styles.row} accessible accessibilityLabel={label}>
      <Svg width={14} height={14} viewBox="0 0 16 16">
        {source === 'STREAM' ? (
          <>
            <Circle cx={8} cy={8} r={2.4} fill={color.accent} />
            <Circle
              cx={8}
              cy={8}
              r={6}
              fill="none"
              stroke={color.accent}
              strokeWidth={1.5}
              strokeDasharray="4 2.3"
            />
          </>
        ) : (
          <>
            <Path
              d="M13.5 8a5.5 5.5 0 1 1-1.6-3.9"
              fill="none"
              stroke={color.accent}
              strokeWidth={1.6}
              strokeLinecap="round"
            />
            <Path d="M13.8 1.8v3.4h-3.4z" fill={color.accent} />
          </>
        )}
      </Svg>
      <AppText variant="small" tone="accent">
        {hasSnapshot
          ? `features as of ${formatClock(featureAsOf)}`
          : 'no attempts yet'}{' '}
        · {source === 'STREAM' ? 'live' : 'recomputed'}
      </AppText>
    </View>
  );
};

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACE.xxs },
});
