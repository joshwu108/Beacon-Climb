import createClient, { type Middleware } from 'openapi-fetch';
import type { paths } from './generated/schema';
import { tokenStore, type TokenStore } from '../features/auth/token-store';
import { ApiError, toApiError } from './errors';

export const DEFAULT_API_BASE_URL = 'http://127.0.0.1:8080';

export const apiBaseUrl: string =
  import.meta.env.VITE_API_BASE_URL?.trim() || DEFAULT_API_BASE_URL;

export const REQUEST_ID_HEADER = 'X-Request-Id';

export const newRequestId = (): string => crypto.randomUUID();

/** Adds the bearer token (when present) and a fresh X-Request-Id to every request. */
export const createAuthMiddleware = (store: TokenStore): Middleware => ({
  onRequest({ request }) {
    const token = store.get();
    if (token !== null && !request.headers.has('Authorization')) {
      request.headers.set('Authorization', `Bearer ${token}`);
    }
    if (!request.headers.has(REQUEST_ID_HEADER)) {
      request.headers.set(REQUEST_ID_HEADER, newRequestId());
    }
    return request;
  },
});

export type ApiClient = ReturnType<typeof createClient<paths>>;

export const createApiClient = (
  options: { baseUrl?: string; store?: TokenStore } = {},
): ApiClient => {
  const client = createClient<paths>({
    baseUrl: options.baseUrl ?? apiBaseUrl,
    // Resolve fetch per request: openapi-fetch would otherwise capture the global at
    // creation time, before MSW's Node interceptor (tests) has replaced it.
    fetch: (request) => globalThis.fetch(request),
  });
  client.use(createAuthMiddleware(options.store ?? tokenStore));
  return client;
};

export const api: ApiClient = createApiClient();

/**
 * Turn an openapi-fetch result into data-or-throw. Thrown errors are always ApiError so
 * TanStack Query consumers can rely on `error.code`, `error.requestId` etc.
 */
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

/** Run a client call; network failures (fetch throws) become ApiError with status 0. */
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
