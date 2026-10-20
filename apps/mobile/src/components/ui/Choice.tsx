import { Pressable, StyleSheet, View } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, HIT, RADIUS, SPACE } from '../../theme/theme';
import { AppText } from './AppText';

export interface ChoiceProps {
  readonly kind: 'radio' | 'checkbox';
  readonly label: string;
  readonly hint?: string;
  readonly selected: boolean;
  readonly onPress: () => void;
  readonly disabled?: boolean;
  readonly invalid?: boolean;
  readonly testID?: string;
}

/**
 * A full-width radio or checkbox row. The mark is a shape (filled dot / tick) inside the
 * control, so selection never depends on colour; the whole row is the touch target.
 */
export const Choice = ({
  kind,
  label,
  hint,
  selected,
  onPress,
  disabled = false,
  invalid = false,
  testID,
}: ChoiceProps) => {
  const { color } = useTheme();
  return (
    <Pressable
      accessibilityRole={kind}
      accessibilityLabel={hint ? `${label}. ${hint}` : label}
      accessibilityState={{ checked: selected, disabled }}
      onPress={onPress}
      disabled={disabled}
      testID={testID}
      style={({ pressed }) => [
        styles.row,
        {
          borderColor: selected
            ? color.action
            : invalid
              ? color.error
              : color.borderSubtle,
          backgroundColor: pressed ? color.surfaceSunken : color.surfaceRaised,
        },
        selected && { borderWidth: 2 },
        disabled && { opacity: 0.5 },
      ]}
    >
      <View
        style={[
          styles.mark,
          kind === 'radio' ? styles.radio : styles.checkbox,
          { borderColor: selected ? color.action : color.border },
          selected && kind === 'checkbox' && { backgroundColor: color.action },
        ]}
      >
        {selected && kind === 'radio' ? (
          <View style={[styles.dot, { backgroundColor: color.action }]} />
        ) : null}
        {selected && kind === 'checkbox' ? (
          <AppText
            variant="label"
            style={[styles.tick, { color: color.textInverse }]}
          >
            ✓
          </AppText>
        ) : null}
      </View>
      <View style={styles.body}>
        <AppText variant="label">{label}</AppText>
        {hint ? (
          <AppText variant="small" tone="muted">
            {hint}
          </AppText>
        ) : null}
      </View>
    </Pressable>
  );
};

const styles = StyleSheet.create({
  row: {
    minHeight: HIT + 12,
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.sm,
    paddingHorizontal: SPACE.md,
    paddingVertical: SPACE.sm,
    borderWidth: BORDER_WIDTH,
    borderRadius: RADIUS.input,
  },
  mark: {
    width: 24,
    height: 24,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  radio: { borderRadius: 12 },
  checkbox: { borderRadius: RADIUS.stamp },
  dot: { width: 12, height: 12, borderRadius: 6 },
  tick: { fontSize: 15, lineHeight: 18 },
  body: { flex: 1, gap: 2 },
});
