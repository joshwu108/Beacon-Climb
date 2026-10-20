import { Pressable, StyleSheet, View } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, HIT, RADIUS, SPACE } from '../../theme/theme';
import { AppText } from './AppText';

export interface StepperProps {
  readonly label: string;
  readonly value: number | null;
  readonly min: number;
  readonly max: number;
  readonly onChange: (value: number) => void;
  /** Shown instead of the number when value is null (an untouched optional answer). */
  readonly emptyText?: string;
  readonly unit?: string;
  readonly disabled?: boolean;
  readonly testID?: string;
}

/** − value + with 44 pt buttons; exposed to assistive tech as an adjustable control. */
export const Stepper = ({
  label,
  value,
  min,
  max,
  onChange,
  emptyText = '—',
  unit,
  disabled = false,
  testID,
}: StepperProps) => {
  const { color } = useTheme();
  const current = value ?? min;
  const spoken =
    value === null ? emptyText : `${value}${unit ? ` ${unit}` : ''}`;
  const dec = () => onChange(value === null ? min : Math.max(min, current - 1));
  const inc = () => onChange(value === null ? min : Math.min(max, current + 1));
  const button = (
    text: string,
    action: () => void,
    isDisabled: boolean,
    name: string,
  ) => (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${name} ${label.toLowerCase()}`}
      accessibilityState={{ disabled: isDisabled }}
      disabled={isDisabled}
      onPress={action}
      testID={testID ? `${testID}-${name}` : undefined}
      style={({ pressed }) => [
        styles.button,
        {
          borderColor: color.action,
          backgroundColor: pressed ? color.surfaceSunken : color.surfaceRaised,
        },
        isDisabled && { opacity: 0.4 },
      ]}
    >
      <AppText variant="title">{text}</AppText>
    </Pressable>
  );
  return (
    <View style={styles.wrap}>
      <AppText variant="stamp" tone="muted">
        {label}
      </AppText>
      <View
        style={styles.row}
        accessible
        accessibilityRole="adjustable"
        accessibilityLabel={label}
        accessibilityValue={{ text: spoken }}
        accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
        onAccessibilityAction={(event) =>
          event.nativeEvent.actionName === 'increment' ? inc() : dec()
        }
      >
        {button(
          '−',
          dec,
          disabled || (value !== null && current <= min),
          'Decrease',
        )}
        <AppText
          variant="title"
          style={styles.value}
          testID={testID ? `${testID}-value` : undefined}
        >
          {spoken}
        </AppText>
        {button(
          '+',
          inc,
          disabled || (value !== null && current >= max),
          'Increase',
        )}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: { gap: SPACE.xs },
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm },
  button: {
    width: HIT + 4,
    height: HIT + 4,
    borderRadius: RADIUS.input,
    borderWidth: BORDER_WIDTH,
    alignItems: 'center',
    justifyContent: 'center',
  },
  value: { minWidth: 72, textAlign: 'center', fontVariant: ['tabular-nums'] },
});
