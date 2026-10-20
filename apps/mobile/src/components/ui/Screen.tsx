import type { ReactNode } from 'react';
import { RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../theme/ThemeProvider';
import { SPACE } from '../../theme/theme';
import { AppText } from './AppText';

export interface ScreenProps {
  readonly eyebrow?: string;
  readonly title?: string;
  readonly lede?: string;
  readonly children: ReactNode;
  /** Something pinned to the right of the title (a stamp, a chip). */
  readonly aside?: ReactNode;
  readonly scroll?: boolean;
  readonly onRefresh?: () => void;
  readonly refreshing?: boolean;
  /** Top inset is skipped when a native header already covers it. */
  readonly underHeader?: boolean;
  readonly testID?: string;
}

/**
 * Page scaffold: safe-area top inset, an eyebrow + display title block with generous space
 * above (hierarchy through rhythm), then content with tighter gaps.
 */
export const Screen = ({
  eyebrow,
  title,
  lede,
  children,
  aside,
  scroll = true,
  onRefresh,
  refreshing = false,
  underHeader = false,
  testID,
}: ScreenProps) => {
  const { color } = useTheme();
  const insets = useSafeAreaInsets();
  const header =
    title !== undefined ? (
      <View style={styles.header}>
        <View style={styles.titleRow}>
          <View style={styles.titleText}>
            {eyebrow ? (
              <AppText variant="stamp" tone="muted">
                {eyebrow}
              </AppText>
            ) : null}
            <AppText variant="screen" accessibilityRole="header">
              {title}
            </AppText>
          </View>
          {aside}
        </View>
        {lede ? (
          <AppText variant="body" tone="muted">
            {lede}
          </AppText>
        ) : null}
      </View>
    ) : null;
  const padding = {
    paddingTop: (underHeader ? 0 : insets.top) + SPACE.lg,
    paddingBottom: SPACE.xxl,
  };
  if (!scroll) {
    return (
      <View
        testID={testID}
        style={[
          styles.fill,
          { backgroundColor: color.surface },
          padding,
          styles.gutter,
        ]}
      >
        {header}
        {children}
      </View>
    );
  }
  return (
    <ScrollView
      testID={testID}
      style={[styles.fill, { backgroundColor: color.surface }]}
      contentContainerStyle={[styles.gutter, styles.content, padding]}
      keyboardShouldPersistTaps="handled"
      refreshControl={
        onRefresh ? (
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        ) : undefined
      }
    >
      {header}
      {children}
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  fill: { flex: 1 },
  gutter: { paddingHorizontal: SPACE.gutter },
  content: { gap: SPACE.lg },
  header: { gap: SPACE.xs, marginBottom: SPACE.xs },
  titleRow: { flexDirection: 'row', alignItems: 'flex-end', gap: SPACE.sm },
  titleText: { flex: 1, gap: SPACE.xxs },
});
