import type { ReactNode } from 'react';
import {
  Pressable,
  StyleSheet,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import {
  BORDER_WIDTH,
  FONTS,
  HIT,
  RADIUS,
  SPACE,
  TYPE,
  paperShadow,
} from '../../theme/theme';
import { AppText } from './AppText';

export type ButtonVariant = 'primary' | 'secondary' | 'quiet';

export interface ButtonProps {
  readonly label: string;
  readonly onPress: () => void;
  readonly variant?: ButtonVariant;
  readonly disabled?: boolean;
  readonly busy?: boolean;
  /** Spoken name when it should say more than the visible label. */
  readonly accessibilityLabel?: string;
  readonly accessibilityHint?: string;
  readonly testID?: string;
  readonly icon?: ReactNode;
  readonly style?: StyleProp<ViewStyle>;
  readonly compact?: boolean;
}

/**
 * Button hierarchy from the token document: primary = ink fill with a hard paper shadow that
 * the press "pushes in" (translate 2px, shadow gone), secondary = ink outline, quiet = text
 * with an underline. Always at least 44 pt tall.
 */
export const Button = ({
  label,
  onPress,
  variant = 'primary',
  disabled = false,
  busy = false,
  accessibilityLabel,
  accessibilityHint,
  testID,
  icon,
  style,
  compact = false,
}: ButtonProps) => {
  const { color } = useTheme();
  const inactive = disabled || busy;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel ?? label}
      accessibilityHint={accessibilityHint}
      accessibilityState={{ disabled: inactive, busy }}
      disabled={inactive}
      onPress={onPress}
      testID={testID}
      hitSlop={compact ? 4 : 0}
      style={({ pressed }) => [
        styles.base,
        compact && styles.compact,
        variant === 'primary' && {
          backgroundColor: pressed ? color.actionPressed : color.action,
          borderColor: color.action,
          ...(pressed || inactive ? {} : paperShadow(color, 3, color.border)),
        },
        variant === 'secondary' && {
          backgroundColor: pressed ? color.surfaceSunken : color.surfaceRaised,
          borderColor: color.action,
        },
        variant === 'quiet' && styles.quiet,
        pressed && variant !== 'quiet' && styles.pressed,
        inactive && styles.inactive,
        style,
      ]}
    >
      <View style={styles.row}>
        {icon}
        <AppText
          variant="label"
          style={[
            styles.label,
            variant === 'quiet' && { textDecorationLine: 'underline' },
            { color: variant === 'primary' ? color.textInverse : color.text },
          ]}
        >
          {busy ? `${label}…` : label}
        </AppText>
      </View>
    </Pressable>
  );
};

const styles = StyleSheet.create({
  base: {
    minHeight: HIT,
    minWidth: HIT,
    paddingHorizontal: SPACE.lg,
    paddingVertical: SPACE.sm,
    borderRadius: RADIUS.input,
    borderWidth: BORDER_WIDTH,
    alignItems: 'center',
    justifyContent: 'center',
  },
  compact: { paddingHorizontal: SPACE.md, paddingVertical: SPACE.xs },
  quiet: {
    borderColor: 'transparent',
    backgroundColor: 'transparent',
    paddingHorizontal: SPACE.xs,
  },
  pressed: { transform: [{ translateX: 2 }, { translateY: 2 }] },
  inactive: { opacity: 0.5 },
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACE.xs },
  label: { ...TYPE.body, fontFamily: FONTS.uiBold },
});
