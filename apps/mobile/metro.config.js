// Metro for apps/mobile. Expo's default config already watches the pnpm workspace root, so
// the web app's MSW handlers (apps/web/src/mocks) and contracts/examples resolve as source.
//
// Dev proxy: the Expo web dev server (http://127.0.0.1:8081) forwards /v1 and /health to the
// compose stack's nginx (BEACON_API_PROXY_TARGET, default http://127.0.0.1:3000). The page
// then calls its own origin, so no CORS change in the API is needed (the API only allows the
// 5173 and 3000 origins). Native builds call EXPO_PUBLIC_API_BASE_URL directly (no CORS).
const http = require('node:http');
const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);

// pnpm keeps each package's dependencies isolated, so a transitive Expo package (e.g.
// expo-asset importing react-native-web on web) cannot see the app's own dependencies.
// Fall back to this app's node_modules, the way a hoisted install would resolve them.
config.resolver.nodeModulesPaths = [
  path.resolve(__dirname, 'node_modules'),
  ...(config.resolver.nodeModulesPaths ?? []),
];

const PROXY_TARGET = new URL(
  process.env.BEACON_API_PROXY_TARGET || 'http://127.0.0.1:3000',
);
const PROXIED_PREFIXES = ['/v1/', '/health/'];

const isProxied = (url) =>
  typeof url === 'string' &&
  PROXIED_PREFIXES.some((prefix) => url.startsWith(prefix));

const proxy = (req, res) => {
  const upstream = http.request(
    {
      protocol: PROXY_TARGET.protocol,
      hostname: PROXY_TARGET.hostname,
      port: PROXY_TARGET.port,
      method: req.method,
      path: req.url,
      // Host and Origin are rewritten to the target so nginx and the API see a same-origin call.
      headers: {
        ...req.headers,
        host: PROXY_TARGET.host,
        ...(req.headers.origin ? { origin: PROXY_TARGET.origin } : {}),
      },
    },
    (upstreamRes) => {
      res.writeHead(upstreamRes.statusCode ?? 502, upstreamRes.headers);
      upstreamRes.pipe(res);
    },
  );
  upstream.on('error', () => {
    if (res.headersSent) return res.end();
    res.writeHead(502, {
      'Content-Type': 'application/json',
      'X-Request-Id': 'mobile-dev-proxy',
    });
    res.end(
      JSON.stringify({
        code: 'UNKNOWN',
        message: `The dev proxy could not reach ${PROXY_TARGET.origin}. Is the stack up (make dev)?`,
        request_id: 'mobile-dev-proxy',
        field_errors: [],
      }),
    );
  });
  req.pipe(upstream);
};

const previousEnhance = config.server.enhanceMiddleware;
config.server.enhanceMiddleware = (middleware, server) => {
  const inner = previousEnhance
    ? previousEnhance(middleware, server)
    : middleware;
  return (req, res, next) =>
    isProxied(req.url) ? proxy(req, res) : inner(req, res, next);
};

module.exports = config;
