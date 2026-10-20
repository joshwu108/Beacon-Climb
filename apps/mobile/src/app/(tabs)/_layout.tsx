import { Redirect } from 'expo-router';
import { Tabs } from 'expo-router/tabs';
import { TabBar } from '../../components/shell/TabBar';
import { useHasToken } from '../../features/auth/useSession';
import { useTheme } from '../../theme/ThemeProvider';

/** Signed-in area: Session, Explore, Log, Scan. */
export default function TabsLayout() {
  const { color } = useTheme();
  const hasToken = useHasToken();
  if (!hasToken) return <Redirect href="/onboarding" />;
  return (
    <Tabs
      tabBar={(props) => <TabBar {...props} />}
      screenOptions={{
        headerShown: false,
        sceneStyle: { backgroundColor: color.surface },
      }}
    >
      <Tabs.Screen name="session" options={{ title: 'Session' }} />
      <Tabs.Screen name="explore" options={{ title: 'Explore' }} />
      <Tabs.Screen name="attempts" options={{ title: 'Log' }} />
      <Tabs.Screen name="scan" options={{ title: 'Scan' }} />
    </Tabs>
  );
}
