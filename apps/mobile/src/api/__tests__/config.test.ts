import {
  DEFAULT_NATIVE_BASE_URL,
  MOCK_BASE_URL,
  resolveApiEnvironment,
} from '../config';

const base = {
  useMocksFlag: undefined,
  baseUrlFlag: undefined,
  platform: 'web',
  webOrigin: 'http://127.0.0.1:8081',
};

describe('resolveApiEnvironment', () => {
  it('mock mode never touches the network', () => {
    expect(resolveApiEnvironment({ ...base, useMocksFlag: 'true' })).toEqual({
      useMocks: true,
      baseUrl: MOCK_BASE_URL,
    });
  });

  it('web uses its own origin so the Expo dev proxy forwards /v1', () => {
    expect(resolveApiEnvironment(base)).toEqual({
      useMocks: false,
      baseUrl: 'http://127.0.0.1:8081',
    });
  });

  it('native defaults to the compose nginx on 127.0.0.1:3000', () => {
    expect(
      resolveApiEnvironment({ ...base, platform: 'ios', webOrigin: undefined })
        .baseUrl,
    ).toBe(DEFAULT_NATIVE_BASE_URL);
  });

  it('an explicit EXPO_PUBLIC_API_BASE_URL wins, without a trailing slash', () => {
    expect(
      resolveApiEnvironment({
        ...base,
        baseUrlFlag: 'http://192.168.1.20:3000/',
      }).baseUrl,
    ).toBe('http://192.168.1.20:3000');
  });
});
