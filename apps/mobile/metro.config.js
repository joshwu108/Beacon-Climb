// Metro for apps/mobile. Expo's default config already watches the pnpm workspace root, so
// the web app's MSW handlers (apps/web/src/mocks) and contracts/examples resolve as source.
//
// Dev proxy (dev-proxy.js): the Expo web dev server (http://127.0.0.1:8081) forwards /v1 and
// /health to the compose stack's nginx (BEACON_API_PROXY_TARGET, default
// http://127.0.0.1:3000). The page then calls its own origin, so no CORS change in the API is
// needed. Native builds call EXPO_PUBLIC_API_BASE_URL directly (no CORS on native).
const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');
const { createDevProxy, isProxied } = require('./dev-proxy');

const config = getDefaultConfig(__dirname);

// pnpm keeps each package's dependencies isolated, so a transitive Expo package (e.g.
// expo-asset importing react-native-web on web) cannot see the app's own dependencies.
// Fall back to this app's node_modules, the way a hoisted install would resolve them.
config.resolver.nodeModulesPaths = [
  path.resolve(__dirname, 'node_modules'),
  ...(config.resolver.nodeModulesPaths ?? []),
];

const proxy = createDevProxy(
  process.env.BEACON_API_PROXY_TARGET || 'http://127.0.0.1:3000',
);

const previousEnhance = config.server.enhanceMiddleware;
config.server.enhanceMiddleware = (middleware, server) => {
  const inner = previousEnhance
    ? previousEnhance(middleware, server)
    : middleware;
  return (req, res, next) =>
    isProxied(req.url) ? proxy(req, res) : inner(req, res, next);
};

module.exports = config;
