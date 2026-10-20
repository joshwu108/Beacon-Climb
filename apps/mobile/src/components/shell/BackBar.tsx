import { router } from 'expo-router';
import { Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, HIT, SPACE } from '../../theme/theme';
import { AppText } from '../ui/AppText';

/** Top bar for pushed screens: a 44 pt "Back" and an optional short context line. */
export const BackBar = ({
  context,
  fallback = '/session',
}: {
  context?: string;
  fallback?: string;
}) => {
  const { color } = useTheme();
  const insets = useSafeAreaInsets();
  const back = () =>
    router.canGoBack() ? router.back() : router.replace(fallback as never);
  return (
    <View
      style={[
        styles.bar,
        {
          paddingTop: insets.top,
          backgroundColor: color.surface,
          borderBottomColor: color.borderSubtle,
        },
      ]}
    >
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Back"
        onPress={back}
        testID="back"
        style={({ pressed }) => [
          styles.back,
          pressed && { backgroundColor: color.surfaceSunken },
        ]}
      >
        <AppText variant="label">‹ Back</AppText>
      </Pressable>
      {context ? (
        <AppText
          variant="stamp"
          tone="muted"
          numberOfLines={1}
          style={styles.context}
        >
          {context}
        </AppText>
      ) : null}
    </View>
  );
};

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.sm,
    paddingHorizontal: SPACE.xs,
    paddingBottom: SPACE.xxs,
    borderBottomWidth: BORDER_WIDTH,
  },
  back: {
    minHeight: HIT,
    minWidth: HIT,
    justifyContent: 'center',
    paddingHorizontal: SPACE.sm,
    borderRadius: 8,
  },
  context: { flex: 1, textAlign: 'right', paddingRight: SPACE.sm },
});
