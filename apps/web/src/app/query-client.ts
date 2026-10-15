import { QueryClient } from '@tanstack/react-query';
import { isApiError } from '../api/errors';

/** Retry only transient failures (network, 5xx, 429); never 4xx client errors. */
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
