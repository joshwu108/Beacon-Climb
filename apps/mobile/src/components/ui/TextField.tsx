import type { Ref } from 'react';
import {
  StyleSheet,
  TextInput,
  View,
  type TextInputInstance,
  type TextInputProps,
} from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import {
  BORDER_WIDTH,
  FONTS,
  HIT,
  RADIUS,
  SPACE,
  TYPE,
} from '../../theme/theme';
import { AppText } from './AppText';

export interface TextFieldProps extends Omit<TextInputProps, 'style'> {
  readonly label: string;
  readonly hint?: string;
  readonly error?: string | null;
  readonly ref?: Ref<TextInputInstance>;
}

/** Labelled text input; the error sits under the field and is part of its accessible name. */
export const TextField = ({
  label,
  hint,
  error,
  ref,
  ...input
}: TextFieldProps) => {
  const { color } = useTheme();
  const description = [hint, error].filter(Boolean).join('. ');
  return (
    <View style={styles.field}>
      <AppText variant="label">{label}</AppText>
      <TextInput
        ref={ref}
        accessibilityLabel={label}
        accessibilityHint={description || undefined}
        aria-invalid={Boolean(error)}
        placeholderTextColor={color.textMuted}
        {...input}
        style={[
          styles.input,
          {
            color: color.text,
            backgroundColor: color.surfaceRaised,
            borderColor: error ? color.error : color.border,
          },
          error ? { borderWidth: 2 } : null,
        ]}
      />
      {hint ? (
        <AppText variant="small" tone="muted">
          {hint}
        </AppText>
      ) : null}
      {error ? (
        <AppText variant="small" tone="error" accessibilityLiveRegion="polite">
          {error}
        </AppText>
      ) : null}
    </View>
  );
};

const styles = StyleSheet.create({
  field: { gap: SPACE.xxs },
  input: {
    minHeight: HIT + 4,
    borderWidth: BORDER_WIDTH,
    borderRadius: RADIUS.input,
    paddingHorizontal: SPACE.sm,
    fontFamily: FONTS.ui,
    // 17 px keeps iOS Safari from zooming on focus.
    fontSize: TYPE.body.fontSize,
  },
});
