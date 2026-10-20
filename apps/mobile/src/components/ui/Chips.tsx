import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, HIT, RADIUS, SPACE } from '../../theme/theme';
import { AppText } from './AppText';

export interface ChipOption<T extends string | number> {
  readonly value: T;
  readonly label: string;
  readonly accessibilityLabel?: string;
}

export interface ChipsProps<T extends string | number> {
  readonly label: string;
  readonly options: readonly ChipOption<T>[];
  readonly value: T | null;
  readonly onChange: (value: T) => void;
  readonly disabled?: boolean;
  readonly testIDPrefix?: string;
  /** Scroll horizontally instead of wrapping (long option lists). */
  readonly scroll?: boolean;
}

/** Single-choice chip row (angles, goals). The selected chip is ink-filled, not just tinted. */
export function Chips<T extends string | number>({
  label,
  options,
  value,
  onChange,
  disabled = false,
  testIDPrefix,
  scroll = false,
}: ChipsProps<T>) {
  const { color } = useTheme();
  const chips = options.map((option) => {
    const selected = option.value === value;
    return (
      <Pressable
        key={String(option.value)}
        accessibilityRole="radio"
        accessibilityLabel={option.accessibilityLabel ?? option.label}
        accessibilityState={{ checked: selected, disabled }}
        disabled={disabled}
        onPress={() => onChange(option.value)}
        testID={testIDPrefix ? `${testIDPrefix}-${option.value}` : undefined}
        style={({ pressed }) => [
          styles.chip,
          {
            backgroundColor: selected
              ? color.action
              : pressed
                ? color.surfaceSunken
                : color.surfaceRaised,
            borderColor: selected ? color.action : color.border,
          },
          disabled && { opacity: 0.5 },
        ]}
      >
        <AppText
          variant="label"
          style={{ color: selected ? color.textInverse : color.text }}
        >
          {option.label}
        </AppText>
      </Pressable>
    );
  });
  return (
    <View
      accessibilityRole="radiogroup"
      accessibilityLabel={label}
      style={styles.group}
    >
      <AppText variant="stamp" tone="muted">
        {label}
      </AppText>
      {scroll ? (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.row}
        >
          {chips}
        </ScrollView>
      ) : (
        <View style={[styles.row, styles.wrap]}>{chips}</View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  group: { gap: SPACE.xs },
  row: { flexDirection: 'row', gap: SPACE.xs },
  wrap: { flexWrap: 'wrap' },
  chip: {
    minHeight: HIT,
    minWidth: HIT,
    paddingHorizontal: SPACE.md,
    borderRadius: RADIUS.pill,
    borderWidth: BORDER_WIDTH,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
