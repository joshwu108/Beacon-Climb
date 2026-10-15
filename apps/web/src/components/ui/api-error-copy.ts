import { isApiError } from '../../api/errors';

export interface ApiErrorCopy {
  readonly title: string;
  readonly detail: string;
  readonly requestId: string | null;
}

const plural = (n: number, unit: string): string =>
  `${n} ${unit}${n === 1 ? '' : 's'}`;

/**
 * User-facing copy for a failed request, keyed by status and code (journeys.md §2:
 * fixture messages are generic, so the UI owns its wording). The request id is kept
 * for the "Details" line so people can quote it.
 */
export const describeApiError = (
  error: unknown,
  fallbackTitle = 'Something went wrong.',
): ApiErrorCopy => {
  if (!isApiError(error)) {
    return {
      title: fallbackTitle,
      detail: error instanceof Error ? error.message : 'Try again.',
      requestId: null,
    };
  }
  const requestId = error.requestId;
  if (error.status === 0) {
    return {
      title: "You're offline or the server can't be reached.",
      detail: 'Check your connection and try again.',
      requestId,
    };
  }
  if (error.status === 429) {
    const wait =
      error.retryAfterSeconds === null
        ? 'a moment'
        : plural(error.retryAfterSeconds, 'second');
    return {
      title: 'Too many attempts.',
      detail: `Try again in ${wait}.`,
      requestId,
    };
  }
  if (error.status === 401) {
    return {
      title: 'Your session ended.',
      detail: 'Sign in again to keep going; your answers are kept.',
      requestId,
    };
  }
  if (error.code === 'INVALID_CREDENTIALS') {
    return {
      title: 'Username or password not recognised.',
      detail: 'Check both and try again.',
      requestId,
    };
  }
  if (error.status === 422) {
    return {
      title: "We couldn't save these choices.",
      detail:
        error.fieldErrors.length > 0
          ? 'Check the highlighted answers and try again.'
          : error.message || 'Check them and try again.',
      requestId,
    };
  }
  if (error.status >= 500) {
    return {
      title: 'Something went wrong on our side.',
      detail: 'Nothing you entered is lost. Try again in a moment.',
      requestId,
    };
  }
  return { title: fallbackTitle, detail: error.message, requestId };
};
