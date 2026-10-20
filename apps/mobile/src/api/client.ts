import { randomUUID } from 'expo-crypto';
import createClient, { type Middleware } from 'openapi-fetch';
import { tokenStore, type TokenStore } from '../auth/token-store';
import { apiEnvironment } from './environment';
import { ApiError, toApiError } from './errors';
import type { paths } from './generated/schema';

export const REQUEST_ID_HEADER = 'X-Request-Id';

export type FetchLike = (request: Request) => Promise<Response>;

/** Adds the bearer token (when present) and a fresh X-Request-Id to every request. */
export const createAuthMiddleware = (store: TokenStore): Middleware => ({
  onRequest({ request }) {
    const token = store.get();
    if (token !== null && !request.headers.has('Authorization')) {
      request.headers.set('Authorization', `Bearer ${token}`);
    }
    if (!request.headers.has(REQUEST_ID_HEADER)) {
      request.headers.set(REQUEST_ID_HEADER, randomUUID());
    }
    return request;
  },
});

/**
 * Mock mode: apps/web's MSW handlers, loaded on first use. A lazy require rather than
 * import(): Jest (babel-preset-expo) cannot run a dynamic import without VM modules.
 */
const mockFetch: FetchLike = (request) => {
  const { handleMockRequest } =
    require('../mocks/mock-fetch') as typeof import('../mocks/mock-fetch');
  return handleMockRequest(request);
};

const networkFetch: FetchLike = (request) => globalThis.fetch(request);

export type ApiClient = ReturnType<typeof createClient<paths>>;

export const createApiClient = (
  options: { baseUrl?: string; store?: TokenStore; fetch?: FetchLike } = {},
): ApiClient => {
  const client = createClient<paths>({
    baseUrl: options.baseUrl ?? apiEnvironment.baseUrl,
    fetch:
      options.fetch ?? (apiEnvironment.useMocks ? mockFetch : networkFetch),
  });
  client.use(createAuthMiddleware(options.store ?? tokenStore));
  return client;
};

export const api: ApiClient = createApiClient();

/** openapi-fetch result -> data, or throw ApiError (so queries can read `error.code`). */
export const unwrap = <T>(result: {
  data?: T;
  error?: unknown;
  response: Response;
}): T => {
  if (result.error !== undefined || !result.response.ok) {
    throw toApiError(
      result.response.status,
      result.error,
      result.response.headers,
    );
  }
  return result.data as T;
};

/** Run a client call; a thrown fetch (network down) becomes ApiError with status 0. */
export const call = async <T>(
  fn: () => Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> => {
  try {
    return unwrap(await fn());
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError({
      status: 0,
      code: 'UNKNOWN',
      message:
        error instanceof Error ? error.message : 'Network request failed',
      requestId: null,
    });
  }
};
