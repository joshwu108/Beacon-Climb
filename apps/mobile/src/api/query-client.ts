import { QueryClient } from '@tanstack/react-query';
import { isApiError } from './errors';

/**
 * Same rules as apps/web's query client: retry only transient failures (network, 5xx, 429),
 * never 4xx; 30 s freshness; mutations never retry (each recommendation records a decision).
 * Kept local so the QueryClient class comes from this app's @tanstack/react-query.
 */
const shouldRetry = (failureCount: number, error: unknown): boolean => {
  if (failureCount >= 2) return false;
  if (!isApiError(error)) return true;
  return error.status === 0 || error.status === 429 || error.status >= 500;
};

export const createQueryClient = (
  options: { retry?: boolean } = {},
): QueryClient =>
  new QueryClient({
    defaultOptions: {
      queries: {
        retry: options.retry === false ? false : shouldRetry,
        staleTime: 30_000,
        refetchOnWindowFocus: false,
      },
      mutations: { retry: false },
    },
  });
