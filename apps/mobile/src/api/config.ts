/**
 * Where the API lives. Read once at start-up from EXPO_PUBLIC_* variables, which Expo
 * inlines at build time.
 *
 * - Mock mode (EXPO_PUBLIC_USE_MOCKS=true): requests never leave the app; they are answered
 *   in-process by apps/web's MSW handlers (src/mocks/mock-fetch.ts). The base URL is a
 *   placeholder origin the handlers match on any host.
 * - Web against the real stack: the page's own origin. The Expo dev server proxies /v1 and
 *   /health to the compose nginx on 127.0.0.1:3000 (metro.config.js), so there is no CORS.
 * - iOS Simulator / device: EXPO_PUBLIC_API_BASE_URL, default http://127.0.0.1:3000 (the
 *   simulator shares the Mac's loopback; a physical phone needs the Mac's LAN address).
 */
export const MOCK_BASE_URL = 'http://mock.beacon.local';
export const DEFAULT_NATIVE_BASE_URL = 'http://127.0.0.1:3000';

export interface ApiEnvironment {
  readonly useMocks: boolean;
  readonly baseUrl: string;
}

export const resolveApiEnvironment = (input: {
  readonly useMocksFlag: string | undefined;
  readonly baseUrlFlag: string | undefined;
  readonly platform: string;
  readonly webOrigin: string | undefined;
}): ApiEnvironment => {
  const useMocks = input.useMocksFlag?.trim().toLowerCase() === 'true';
  if (useMocks) return { useMocks, baseUrl: MOCK_BASE_URL };
  const explicit = input.baseUrlFlag?.trim();
  if (explicit) return { useMocks, baseUrl: explicit.replace(/\/+$/, '') };
  if (input.platform === 'web' && input.webOrigin) {
    return { useMocks, baseUrl: input.webOrigin };
  }
  return { useMocks, baseUrl: DEFAULT_NATIVE_BASE_URL };
};
