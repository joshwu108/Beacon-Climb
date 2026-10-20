import { StyleSheet, View, type DimensionValue } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { RADIUS, SPACE } from '../../theme/theme';
import { AppText } from './AppText';

/** Static placeholder bars (no shimmer: nothing loops) plus a spoken status line. */
export const Skeleton = ({
  label,
  widths = ['60%', '90%', '40%'],
}: {
  label: string;
  widths?: readonly DimensionValue[];
}) => {
  const { color } = useTheme();
  return (
    <View
      style={styles.wrap}
      accessibilityRole="progressbar"
      accessibilityLabel={label}
    >
      <AppText variant="small" tone="muted">
        {label}
      </AppText>
      {widths.map((width, index) => (
        <View
          key={index}
          style={[styles.bar, { width, backgroundColor: color.surfaceSunken }]}
        />
      ))}
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: { gap: SPACE.xs },
  bar: { height: 14, borderRadius: RADIUS.stamp },
});
