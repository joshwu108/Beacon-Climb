import {
  AtkinsonHyperlegibleNext_400Regular,
  AtkinsonHyperlegibleNext_500Medium,
  AtkinsonHyperlegibleNext_700Bold,
} from '@expo-google-fonts/atkinson-hyperlegible-next';
import { YoungSerif_400Regular } from '@expo-google-fonts/young-serif';
import { QueryClientProvider } from '@tanstack/react-query';
import { createQueryClient } from '../api/query-client';
import { useFonts } from 'expo-font';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { PhoneFrame } from '../components/shell/PhoneFrame';
import { Skeleton } from '../components/ui/Skeleton';
import { ThemeProvider, useTheme } from '../theme/ThemeProvider';
import { SPACE } from '../theme/theme';

const AppStack = () => {
  const { color, scheme } = useTheme();
  const [fontsLoaded, fontError] = useFonts({
    YoungSerif_400Regular,
    AtkinsonHyperlegibleNext_400Regular,
    AtkinsonHyperlegibleNext_500Medium,
    AtkinsonHyperlegibleNext_700Bold,
  });
  return (
    <PhoneFrame>
      <StatusBar style={scheme === 'dark' ? 'light' : 'dark'} />
      {fontsLoaded || fontError ? (
        <Stack
          screenOptions={{
            headerShown: false,
            contentStyle: { backgroundColor: color.surface },
            animation: 'slide_from_right',
          }}
        >
          <Stack.Screen name="index" options={{ animation: 'none' }} />
          <Stack.Screen name="onboarding" options={{ animation: 'fade' }} />
          <Stack.Screen name="(tabs)" options={{ animation: 'fade' }} />
          <Stack.Screen
            name="log-attempt"
            options={{ animation: 'slide_from_bottom' }}
          />
        </Stack>
      ) : (
        <View style={[styles.loading, { backgroundColor: color.surface }]}>
          <Skeleton label="Opening Beacon Climb" />
        </View>
      )}
    </PhoneFrame>
  );
};

/** Root: theme (system light/dark), one query client (apps/web's retry rules), the stack. */
export default function RootLayout() {
  const [queryClient] = useState(() => createQueryClient());
  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <AppStack />
      </QueryClientProvider>
    </ThemeProvider>
  );
}

const styles = StyleSheet.create({
  loading: { flex: 1, justifyContent: 'center', padding: SPACE.gutter },
});
