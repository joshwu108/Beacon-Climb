import { StyleSheet, View } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, RADIUS, SPACE } from '../../theme/theme';
import { AppText } from './AppText';

export type StampTone = 'ink' | 'accent' | 'warning' | 'error' | 'success';

export interface StampProps {
  readonly label: string;
  readonly tone?: StampTone;
  readonly accessibilityLabel?: string;
}

/** Square uppercase stamp: SYNTHETIC DEMO BOARD, RECOMMENDED, NOT ENOUGH DATA, RETRACTED. */
export const Stamp = ({
  label,
  tone = 'ink',
  accessibilityLabel,
}: StampProps) => {
  const { color } = useTheme();
  const palette = {
    ink: { fg: color.text, bg: 'transparent', border: color.border },
    accent: { fg: color.accent, bg: color.accentBg, border: color.accent },
    warning: { fg: color.warning, bg: color.warningBg, border: color.warning },
    error: { fg: color.error, bg: color.errorBg, border: color.error },
    success: { fg: color.success, bg: color.successBg, border: color.success },
  }[tone];
  return (
    <View
      accessible
      accessibilityLabel={accessibilityLabel ?? label}
      style={[
        styles.stamp,
        { backgroundColor: palette.bg, borderColor: palette.border },
      ]}
    >
      <AppText variant="stamp" style={{ color: palette.fg }}>
        {label}
      </AppText>
    </View>
  );
};

const styles = StyleSheet.create({
  stamp: {
    alignSelf: 'flex-start',
    borderWidth: BORDER_WIDTH,
    borderRadius: RADIUS.stamp,
    paddingHorizontal: SPACE.xs,
    paddingVertical: 2,
  },
});
