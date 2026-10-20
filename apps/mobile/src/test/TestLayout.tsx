import { QueryClientProvider } from '@tanstack/react-query';
import { Slot } from 'expo-router';
import { useState } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { createQueryClient } from '../api/query-client';
import { ThemeProvider } from '../theme/ThemeProvider';

const METRICS = {
  frame: { x: 0, y: 0, width: 390, height: 844 },
  insets: { top: 47, bottom: 34, left: 0, right: 0 },
};

/** Root layout for router tests: the real providers (no retries), no fonts, no phone frame. */
export default function TestLayout() {
  const [client] = useState(() => createQueryClient({ retry: false }));
  return (
    <SafeAreaProvider initialMetrics={METRICS}>
      <ThemeProvider scheme="light">
        <QueryClientProvider client={client}>
          <Slot />
        </QueryClientProvider>
      </ThemeProvider>
    </SafeAreaProvider>
  );
}
