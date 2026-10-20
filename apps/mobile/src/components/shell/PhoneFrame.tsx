import type { ReactNode } from 'react';
import { Platform, StyleSheet, View, useWindowDimensions } from 'react-native';
import {
  SafeAreaFrameContext,
  SafeAreaInsetsContext,
  SafeAreaProvider,
  initialWindowMetrics,
} from 'react-native-safe-area-context';
import { apiEnvironment } from '../../api/environment';
import { useTheme } from '../../theme/ThemeProvider';
import { PHONE_FRAME, RADIUS, SPACE, paperShadow } from '../../theme/theme';
import { AppText } from '../ui/AppText';

/* Insets of a notched phone (status bar with the island, home indicator). */
const FRAME_INSETS = { top: 47, bottom: 34, left: 0, right: 0 } as const;
const BEZEL = 10;
/** The phone body stays dark in dark mode (the ink token turns cream there). */
const BEZEL_DARK = '#2a261c';
const FRAME_RECT = {
  x: 0,
  y: 0,
  width: PHONE_FRAME.width,
  height: PHONE_FRAME.height,
} as const;

/**
 * Web demo on a wide screen: the app sits in a 390 x 844 phone on a desk, with simulated
 * safe-area insets so the layout matches a real phone. On a phone-width browser and on
 * native, the app fills the screen and real insets apply.
 */
export const PhoneFrame = ({ children }: { children: ReactNode }) => {
  const { width, height } = useWindowDimensions();
  const { color, scheme } = useTheme();
  const framed = Platform.OS === 'web' && width >= PHONE_FRAME.minViewport;

  if (!framed) {
    return (
      <SafeAreaProvider initialMetrics={initialWindowMetrics}>
        {children}
      </SafeAreaProvider>
    );
  }

  const scale = Math.min(
    1,
    (height - SPACE.xl * 2) / (PHONE_FRAME.height + BEZEL * 2),
  );
  return (
    <View style={[styles.desk, { backgroundColor: color.desk }]}>
      <View
        style={[
          styles.phone,
          {
            backgroundColor: scheme === 'dark' ? BEZEL_DARK : color.text,
            transform: [{ scale }],
            ...paperShadow(color, 10, color.borderSubtle),
          },
        ]}
      >
        <View style={[styles.screen, { backgroundColor: color.surface }]}>
          {/* On web SafeAreaProvider measures the browser (no insets), so the frame
              supplies a notched phone's insets through the contexts directly. */}
          <SafeAreaFrameContext.Provider value={FRAME_RECT}>
            <SafeAreaInsetsContext.Provider value={FRAME_INSETS}>
              {children}
            </SafeAreaInsetsContext.Provider>
          </SafeAreaFrameContext.Provider>
          <View
            style={[
              styles.island,
              styles.passThrough,
              { backgroundColor: color.text },
            ]}
            aria-hidden
          />
          <View
            style={[
              styles.homeIndicator,
              styles.passThrough,
              { backgroundColor: color.text },
            ]}
            aria-hidden
          />
        </View>
      </View>
      <View style={styles.caption}>
        <AppText variant="stamp" tone="muted">
          Beacon Climb · mobile demo ·{' '}
          {apiEnvironment.useMocks
            ? 'mock data, synthetic board'
            : 'local stack'}
        </AppText>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  desk: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  phone: {
    width: PHONE_FRAME.width + BEZEL * 2,
    height: PHONE_FRAME.height + BEZEL * 2,
    borderRadius: 56,
    padding: BEZEL,
  },
  screen: {
    width: PHONE_FRAME.width,
    height: PHONE_FRAME.height,
    borderRadius: 46,
    overflow: 'hidden',
  },
  island: {
    position: 'absolute',
    top: 11,
    alignSelf: 'center',
    width: 120,
    height: 34,
    borderRadius: RADIUS.pill,
  },
  homeIndicator: {
    position: 'absolute',
    bottom: 8,
    alignSelf: 'center',
    width: 134,
    height: 5,
    borderRadius: RADIUS.pill,
    opacity: 0.85,
  },
  passThrough: { pointerEvents: 'none' },
  caption: { position: 'absolute', bottom: SPACE.md },
});
