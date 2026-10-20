import { StyleSheet, View } from 'react-native';
import Svg, { Circle, Path, Rect } from 'react-native-svg';
import { AppText } from '../../components/ui/AppText';
import { Button } from '../../components/ui/Button';
import { Screen } from '../../components/ui/Screen';
import { Stamp } from '../../components/ui/Stamp';
import { useTheme } from '../../theme/ThemeProvider';
import { SPACE } from '../../theme/theme';

/**
 * Placeholder for E07 (board photo upload). Deliberately a stub: no camera permission, no
 * image picker, no upload endpoint (the contract has none yet). It marks where the
 * feature will live and says plainly that it isn't built.
 */
export default function ScanTab() {
  const { color } = useTheme();
  return (
    <Screen
      eyebrow="Coming later · E07"
      title="Scan a board"
      testID="scan-screen"
    >
      <Stamp label="Not built yet" tone="warning" />
      <View
        style={[styles.viewfinder, { backgroundColor: color.surfaceSunken }]}
        accessible
        accessibilityRole="image"
        accessibilityLabel="Illustration: an empty viewfinder framing a board"
      >
        <Svg width="100%" height="100%" viewBox="0 0 300 380">
          <Rect
            x={50}
            y={50}
            width={200}
            height={280}
            rx={10}
            fill={color.boardFace}
            stroke={color.border}
            strokeWidth={2}
            strokeDasharray="8 6"
          />
          {[0, 1, 2, 3, 4].flatMap((r) =>
            [0, 1, 2, 3].map((c) => (
              <Circle
                key={`${r}-${c}`}
                cx={85 + c * 43}
                cy={90 + r * 52}
                r={6}
                fill={color.holdNeutral}
                opacity={0.5}
              />
            )),
          )}
          <Path
            d="M20 70V20h50M230 20h50v50M280 310v50h-50M70 360H20v-50"
            stroke={color.text}
            strokeWidth={5}
            fill="none"
            strokeLinecap="round"
          />
        </Svg>
      </View>
      <AppText>
        One day you'll photograph a board here and Beacon will match it to
        routes. That needs the E07 work first: an upload contract, storage and a
        privacy review.
      </AppText>
      <AppText variant="small" tone="muted">
        This screen asks for no camera access and sends nothing.
      </AppText>
      <Button
        label="Scan (not available yet)"
        onPress={() => undefined}
        disabled
        accessibilityHint="Board scanning is not built yet"
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  viewfinder: {
    aspectRatio: 300 / 380,
    borderRadius: 16,
    overflow: 'hidden',
    marginVertical: SPACE.xs,
  },
});
