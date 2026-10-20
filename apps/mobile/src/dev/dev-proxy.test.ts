/**
 * @jest-environment node
 *
 * The Metro dev proxy (dev-proxy.js) against a throwaway upstream: what is forwarded, the
 * Host / Origin rewrite that keeps the API's CORS allow-list unchanged, and the 502 envelope
 * when the stack is down. Requests use node:http (the jest-expo preset mocks global fetch).
 */
import http, { type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

const { createDevProxy, isProxied } = require('../../dev-proxy') as {
  createDevProxy: (target: string) => http.RequestListener;
  isProxied: (url: string | undefined) => boolean;
};

const listen = (server: Server): Promise<number> =>
  new Promise((resolve) =>
    server.listen(0, '127.0.0.1', () =>
      resolve((server.address() as AddressInfo).port),
    ),
  );

const close = (server: Server): Promise<void> =>
  new Promise((resolve) => {
    server.closeAllConnections();
    server.close(() => resolve());
  });

const send = (
  port: number,
  path: string,
  init: {
    method?: string;
    headers?: Record<string, string>;
    body?: string;
  } = {},
): Promise<{ status: number; headers: IncomingHttpHeaders; body: string }> =>
  new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: '127.0.0.1',
        port,
        path,
        method: init.method ?? 'GET',
        headers: init.headers,
        agent: false,
      },
      (res) => {
        let body = '';
        res.on('data', (chunk) => (body += chunk));
        res.on('end', () =>
          resolve({ status: res.statusCode ?? 0, headers: res.headers, body }),
        );
      },
    );
    req.on('error', reject);
    req.end(init.body);
  });

describe('dev proxy', () => {
  let seen: {
    url?: string;
    method?: string;
    headers?: IncomingHttpHeaders;
    body?: string;
  } = {};
  const upstream = http.createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      seen = { url: req.url, method: req.method, headers: req.headers, body };
      res.writeHead(201, {
        'Content-Type': 'application/json',
        'X-Request-Id': 'up-1',
      });
      res.end('{"ok":true}');
    });
  });
  let upstreamPort = 0;
  let front: Server;
  let frontPort = 0;

  beforeAll(async () => {
    upstreamPort = await listen(upstream);
    front = http.createServer(
      createDevProxy(`http://127.0.0.1:${upstreamPort}`),
    );
    frontPort = await listen(front);
  });
  afterAll(async () => {
    await close(front);
    await close(upstream);
  });

  it('proxies only /v1/ and /health/', () => {
    expect(isProxied('/v1/me')).toBe(true);
    expect(isProxied('/health/ready')).toBe(true);
    expect(isProxied('/index.bundle')).toBe(false);
    expect(isProxied('/v1')).toBe(false);
  });

  it('forwards method, path, body and auth, and rewrites Host and Origin to the target', async () => {
    const response = await send(frontPort, '/v1/auth/register?x=1', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer t',
        Origin: 'http://127.0.0.1:8081',
      },
      body: '{"username":"a"}',
    });
    expect(response.status).toBe(201);
    expect(response.headers['x-request-id']).toBe('up-1');
    expect(JSON.parse(response.body)).toEqual({ ok: true });
    expect(seen.method).toBe('POST');
    expect(seen.url).toBe('/v1/auth/register?x=1');
    expect(seen.body).toBe('{"username":"a"}');
    expect(seen.headers?.authorization).toBe('Bearer t');
    expect(seen.headers?.host).toBe(`127.0.0.1:${upstreamPort}`);
    expect(seen.headers?.origin).toBe(`http://127.0.0.1:${upstreamPort}`);
  });

  it('answers 502 with an ErrorEnvelope when the stack is down', async () => {
    const dead = http.createServer(createDevProxy('http://127.0.0.1:1'));
    const port = await listen(dead);
    const response = await send(port, '/v1/me');
    expect(response.status).toBe(502);
    expect(JSON.parse(response.body)).toMatchObject({
      code: 'UNKNOWN',
      request_id: 'mobile-dev-proxy',
      field_errors: [],
    });
    await close(dead);
  });
});
