import { router } from 'expo-router';
import { ActivityIndicator, FlatList, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { AppText } from '../../components/ui/AppText';
import { Banner } from '../../components/ui/Banner';
import { Button } from '../../components/ui/Button';
import { Skeleton } from '../../components/ui/Skeleton';
import { AttemptRow } from '../../features/attempts/AttemptRow';
import { useAttempts } from '../../features/attempts/useAttempts';
import { useTheme } from '../../theme/ThemeProvider';
import { SPACE } from '../../theme/theme';

/** The logbook: every attempt, newest first, with corrections and retractions. */
export default function AttemptsTab() {
  const { color } = useTheme();
  const insets = useSafeAreaInsets();
  const attempts = useAttempts();
  const items = attempts.data?.pages.flatMap((page) => page.items) ?? [];
  const sends = items.filter((a) => a.status === 'ACTIVE' && a.sent).length;
  const active = items.filter((a) => a.status === 'ACTIVE').length;

  return (
    <FlatList
      testID="attempts-list"
      style={{ backgroundColor: color.surface }}
      contentContainerStyle={styles.content}
      data={items}
      keyExtractor={(item) => item.attempt_id}
      onRefresh={() => void attempts.refetch()}
      refreshing={attempts.isRefetching && !attempts.isFetchingNextPage}
      ListHeaderComponent={
        <View style={[styles.header, { paddingTop: insets.top + SPACE.lg }]}>
          <AppText variant="stamp" tone="muted">
            Logbook
          </AppText>
          <AppText variant="screen" accessibilityRole="header">
            Attempt log
          </AppText>
          {items.length > 0 ? (
            <AppText tone="muted" testID="attempts-summary">
              {active} logged here · {sends} sent. Counts cover the entries
              loaded below.
            </AppText>
          ) : null}
          {attempts.isPending ? (
            <Skeleton label="Loading your attempts" />
          ) : null}
          {attempts.isError ? (
            <ApiErrorBanner
              error={attempts.error}
              title="Your attempts couldn't be loaded."
              onRetry={() => void attempts.refetch()}
            />
          ) : null}
        </View>
      }
      renderItem={({ item }) => <AttemptRow attempt={item} />}
      onEndReachedThreshold={0.4}
      onEndReached={() => {
        if (attempts.hasNextPage && !attempts.isFetchingNextPage)
          void attempts.fetchNextPage();
      }}
      ListEmptyComponent={
        attempts.isSuccess ? (
          <Banner
            title="Nothing logged yet."
            actionLabel="Build a session"
            onAction={() => router.navigate('/session')}
          >
            Log an attempt from a session pick or any route, and it shows up
            here.
          </Banner>
        ) : undefined
      }
      ListFooterComponent={
        attempts.isFetchingNextPage ? (
          <ActivityIndicator
            accessibilityLabel="Loading more attempts"
            color={color.text}
            style={styles.more}
          />
        ) : attempts.hasNextPage ? (
          <Button
            label="Load older attempts"
            variant="secondary"
            onPress={() => void attempts.fetchNextPage()}
            style={styles.more}
          />
        ) : undefined
      }
    />
  );
}

const styles = StyleSheet.create({
  content: { paddingHorizontal: SPACE.gutter, paddingBottom: SPACE.xxl },
  header: { gap: SPACE.sm, marginBottom: SPACE.sm },
  more: { marginTop: SPACE.lg, alignSelf: 'center' },
});
