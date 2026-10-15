import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import attemptRequest from '../../../../contracts/examples/createAttempt/request.json';
import consentResponse from '../../../../contracts/examples/getConsentDocument/response.json';
import { server } from '../mocks/server';
import { createTokenStore } from '../features/auth/token-store';
import { ApiError } from './errors';
import { REQUEST_ID_HEADER, call, createApiClient } from './client';

const BASE = 'http://api.test';
const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const signedInClient = () => {
  const store = createTokenStore();
  store.set({ token: 'opaque-demo-token', expiresAt: '2999-01-01T00:00:00Z' });
  return createApiClient({ baseUrl: BASE, store });
};

describe('api client', () => {
  it('fetches a public operation through the mocks and returns the fixture body', async () => {
    const client = createApiClient({
      baseUrl: BASE,
      store: createTokenStore(),
    });
    const doc = await call(() => client.GET('/v1/consent/current'));
    expect(doc).toEqual(consentResponse.body);
  });

  it('adds the bearer token and a fresh X-Request-Id to every request', async () => {
    const seen: string[] = [];
    server.use(
      http.get(`${BASE}/v1/me`, ({ request }) => {
        seen.push(request.headers.get(REQUEST_ID_HEADER) ?? '');
        expect(request.headers.get('Authorization')).toBe(
          'Bearer opaque-demo-token',
        );
        return HttpResponse.json({}, { status: 200 });
      }),
    );
    const client = signedInClient();
    await call(() => client.GET('/v1/me'));
    await call(() => client.GET('/v1/me'));
    expect(seen).toHaveLength(2);
    expect(seen[0]).toMatch(UUID_RE);
    expect(seen[1]).toMatch(UUID_RE);
    expect(seen[0]).not.toBe(seen[1]);
  });

  it('sends no Authorization header when the store is empty and surfaces the mock 401 as ApiError', async () => {
    const client = createApiClient({
      baseUrl: BASE,
      store: createTokenStore(),
    });
    const error = await call(() => client.GET('/v1/me')).catch(
      (e: unknown) => e,
    );
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
    expect((error as ApiError).code).toBe('UNAUTHENTICATED');
  });

  it('createAttempt with the same Idempotency-Key twice is 201 then 200 with the replay fixture', async () => {
    const client = signedInClient();
    const send = () =>
      client.POST('/v1/me/attempts', {
        params: {
          header: {
            'Idempotency-Key': attemptRequest.headers['Idempotency-Key'],
          },
        },
        body: attemptRequest.body,
      });
    const first = await send();
    const second = await send();
    expect(first.response.status).toBe(201);
    expect(second.response.status).toBe(200);
    expect(second.data?.attempt_id).toBe(first.data?.attempt_id);
  });

  it('createAttempt with the same key but a different body is IDEMPOTENCY_CONFLICT', async () => {
    const client = signedInClient();
    const key = attemptRequest.headers['Idempotency-Key'];
    await call(() =>
      client.POST('/v1/me/attempts', {
        params: { header: { 'Idempotency-Key': key } },
        body: attemptRequest.body,
      }),
    );
    const error = await call(() =>
      client.POST('/v1/me/attempts', {
        params: { header: { 'Idempotency-Key': key } },
        body: { ...attemptRequest.body, sent: false },
      }),
    ).catch((e: unknown) => e);
    expect((error as ApiError).code).toBe('IDEMPOTENCY_CONFLICT');
    expect((error as ApiError).status).toBe(409);
  });

  it('wraps network failures as ApiError with status 0', async () => {
    server.use(http.get(`${BASE}/v1/boards`, () => HttpResponse.error()));
    const client = signedInClient();
    const error = await call(() => client.GET('/v1/boards')).catch(
      (e: unknown) => e,
    );
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(0);
    expect((error as ApiError).code).toBe('UNKNOWN');
  });
});
