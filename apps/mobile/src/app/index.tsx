import { Redirect } from 'expo-router';
import { useEffect } from 'react';
import { StyleSheet, View } from 'react-native';
import { isApiError } from '../api/errors';
import { tokenStore } from '../auth/token-store';
import { ApiErrorBanner } from '../components/ui/ApiErrorBanner';
import { Skeleton } from '../components/ui/Skeleton';
import { firstIncompleteStep } from '../features/onboarding/steps';
import { useHasToken } from '../features/auth/useSession';
import { useMe } from '../features/auth/useMe';
import { useTheme } from '../theme/ThemeProvider';
import { SPACE } from '../theme/theme';

/** Entry gate: no session or unfinished setup -> onboarding; otherwise the session tab. */
export default function Index() {
  const { color } = useTheme();
  const hasToken = useHasToken();
  const me = useMe(hasToken);
  const isDeadToken = isApiError(me.error) && me.error.status === 401;

  useEffect(() => {
    if (isDeadToken) tokenStore.clear();
  }, [isDeadToken]);

  if (!hasToken || isDeadToken) return <Redirect href="/onboarding" />;
  if (me.isError) {
    return (
      <View style={[styles.center, { backgroundColor: color.surface }]}>
        <ApiErrorBanner
          error={me.error}
          title="Your setup couldn't be checked."
          onRetry={() => void me.refetch()}
        />
      </View>
    );
  }
  if (me.isPending) {
    return (
      <View style={[styles.center, { backgroundColor: color.surface }]}>
        <Skeleton label="Checking your setup" />
      </View>
    );
  }
  return firstIncompleteStep(true, me.data) === null ? (
    <Redirect href="/session" />
  ) : (
    <Redirect href="/onboarding" />
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, justifyContent: 'center', padding: SPACE.gutter },
});
