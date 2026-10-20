import type { ReactNode } from 'react';
import {
  Text,
  type StyleProp,
  type TextProps,
  type TextStyle,
} from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { FONTS, TYPE, type Palette } from '../../theme/theme';

export type TextVariant =
  | 'grade'
  | 'screen'
  | 'title'
  | 'body'
  | 'small'
  | 'stamp'
  | 'label';
export type TextTone =
  | 'default'
  | 'muted'
  | 'accent'
  | 'warning'
  | 'error'
  | 'success'
  | 'inverse';

const VARIANT_STYLE: Record<TextVariant, TextStyle> = {
  grade: { ...TYPE.grade, fontFamily: FONTS.display },
  screen: { ...TYPE.screen, fontFamily: FONTS.display },
  title: { ...TYPE.title, fontFamily: FONTS.display },
  body: { ...TYPE.body, fontFamily: FONTS.ui },
  small: { ...TYPE.small, fontFamily: FONTS.ui },
  label: { ...TYPE.body, fontFamily: FONTS.uiBold },
  stamp: {
    ...TYPE.stamp,
    fontFamily: FONTS.uiBold,
    textTransform: 'uppercase',
  },
};

const toneColor = (tone: TextTone, color: Palette): string =>
  ({
    default: color.text,
    muted: color.textMuted,
    accent: color.accent,
    warning: color.warning,
    error: color.error,
    success: color.success,
    inverse: color.textInverse,
  })[tone];

export interface AppTextProps extends Omit<TextProps, 'style'> {
  readonly variant?: TextVariant;
  readonly tone?: TextTone;
  readonly style?: StyleProp<TextStyle>;
  readonly children?: ReactNode;
}

/** Text in the type scale: display face for titles and grades, UI face for everything else. */
export const AppText = ({
  variant = 'body',
  tone = 'default',
  style,
  ...rest
}: AppTextProps) => {
  const { color } = useTheme();
  return (
    <Text
      maxFontSizeMultiplier={variant === 'grade' ? 1.3 : 2}
      {...rest}
      style={[VARIANT_STYLE[variant], { color: toneColor(tone, color) }, style]}
    />
  );
};
