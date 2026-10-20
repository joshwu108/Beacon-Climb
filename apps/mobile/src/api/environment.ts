import { Platform } from 'react-native';
import { resolveApiEnvironment, type ApiEnvironment } from './config';

const webOrigin = (): string | undefined => {
  try {
    return globalThis.location?.origin;
  } catch {
    return undefined;
  }
};

export const apiEnvironment: ApiEnvironment = resolveApiEnvironment({
  useMocksFlag: process.env.EXPO_PUBLIC_USE_MOCKS,
  baseUrlFlag: process.env.EXPO_PUBLIC_API_BASE_URL,
  platform: Platform.OS,
  webOrigin: webOrigin(),
});
