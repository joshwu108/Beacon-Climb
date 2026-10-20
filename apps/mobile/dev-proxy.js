// Dev-only reverse proxy used by metro.config.js: the Expo web dev server forwards /v1 and
// /health to the compose stack's nginx, so the page calls its own origin and the API's CORS
// allow-list (5173 and 3000 only) needs no change. Host and Origin are rewritten to the
// target; an unreachable target answers 502 with a contract-shaped ErrorEnvelope.
const http = require('node:http');

const PROXIED_PREFIXES = ['/v1/', '/health/'];

const isProxied = (url) =>
  typeof url === 'string' &&
  PROXIED_PREFIXES.some((prefix) => url.startsWith(prefix));

const createDevProxy = (targetUrl) => {
  const target = new URL(targetUrl);
  return (req, res) => {
    const upstream = http.request(
      {
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port,
        method: req.method,
        path: req.url,
        headers: {
          ...req.headers,
          host: target.host,
          ...(req.headers.origin ? { origin: target.origin } : {}),
        },
      },
      (upstreamRes) => {
        res.writeHead(upstreamRes.statusCode ?? 502, upstreamRes.headers);
        upstreamRes.pipe(res);
      },
    );
    upstream.on('error', () => {
      if (res.headersSent) {
        res.end();
        return;
      }
      res.writeHead(502, {
        'Content-Type': 'application/json',
        'X-Request-Id': 'mobile-dev-proxy',
      });
      res.end(
        JSON.stringify({
          code: 'UNKNOWN',
          message: `The dev proxy could not reach ${target.origin}. Is the stack up (make dev)?`,
          request_id: 'mobile-dev-proxy',
          field_errors: [],
        }),
      );
    });
    req.pipe(upstream);
  };
};

module.exports = { createDevProxy, isProxied, PROXIED_PREFIXES };
