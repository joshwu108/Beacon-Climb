import type { ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, RADIUS, SPACE } from '../../theme/theme';
import { AppText } from './AppText';
import { Button } from './Button';

export type BannerTone = 'neutral' | 'warning' | 'error' | 'success';

export interface BannerProps {
  readonly tone?: BannerTone;
  readonly title: string;
  readonly children?: ReactNode;
  readonly actionLabel?: string;
  readonly onAction?: () => void;
  readonly testID?: string;
}

const KIND_WORD: Record<BannerTone, string> = {
  neutral: 'Note',
  warning: 'Heads up',
  error: 'Problem',
  success: 'Saved',
};

/**
 * Status banner: a tinted panel with a full hairline border and a stamped kind word, so the
 * tone is carried by text as well as colour. Errors are announced as alerts.
 */
export const Banner = ({
  tone = 'neutral',
  title,
  children,
  actionLabel,
  onAction,
  testID,
}: BannerProps) => {
  const { color } = useTheme();
  const palette = {
    neutral: { bg: color.surfaceSunken, fg: color.text, border: color.border },
    warning: { bg: color.warningBg, fg: color.warning, border: color.warning },
    error: { bg: color.errorBg, fg: color.error, border: color.error },
    success: { bg: color.successBg, fg: color.success, border: color.success },
  }[tone];
  return (
    <View
      testID={testID}
      accessibilityRole={tone === 'error' ? 'alert' : 'summary'}
      accessibilityLiveRegion={tone === 'error' ? 'assertive' : 'polite'}
      style={[
        styles.banner,
        { backgroundColor: palette.bg, borderColor: palette.border },
      ]}
    >
      <AppText variant="stamp" style={{ color: palette.fg }}>
        {KIND_WORD[tone]}
      </AppText>
      <AppText variant="label">{title}</AppText>
      {typeof children === 'string' ? (
        <AppText variant="small">{children}</AppText>
      ) : (
        children
      )}
      {actionLabel && onAction ? (
        <Button
          label={actionLabel}
          onPress={onAction}
          variant="secondary"
          compact
          style={styles.action}
        />
      ) : null}
    </View>
  );
};

const styles = StyleSheet.create({
  banner: {
    borderWidth: BORDER_WIDTH,
    borderRadius: RADIUS.input,
    padding: SPACE.md,
    gap: SPACE.xxs,
  },
  action: { alignSelf: 'flex-start', marginTop: SPACE.xs },
});
