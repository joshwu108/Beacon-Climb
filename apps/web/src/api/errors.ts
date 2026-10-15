import type { components } from './generated/schema';

export type ErrorEnvelope = components['schemas']['ErrorEnvelope'];
export type ErrorCode = components['schemas']['ErrorCode'];
export type FieldError = ErrorEnvelope['field_errors'][number];

/**
 * Typed API failure. Wraps the contract ErrorEnvelope with the HTTP status; when a
 * response is not an envelope (proxy error, network failure) `code` is `UNKNOWN` and
 * `requestId` falls back to the X-Request-Id response header or the one we sent.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode | 'UNKNOWN';
  readonly requestId: string | null;
  readonly fieldErrors: readonly FieldError[];
  readonly currentRevision: number | null;
  readonly retryAfterSeconds: number | null;

  constructor(init: {
    status: number;
    code: ErrorCode | 'UNKNOWN';
    message: string;
    requestId: string | null;
    fieldErrors?: readonly FieldError[];
    currentRevision?: number | null;
    retryAfterSeconds?: number | null;
  }) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.requestId = init.requestId;
    this.fieldErrors = init.fieldErrors ?? [];
    this.currentRevision = init.currentRevision ?? null;
    this.retryAfterSeconds = init.retryAfterSeconds ?? null;
  }

  /** Field errors keyed by `field` for form mapping (422 handling in BC-0115+). */
  fieldErrorsByField(): ReadonlyMap<string, FieldError> {
    return new Map(this.fieldErrors.map((fe) => [fe.field, fe]));
  }
}

export const isErrorEnvelope = (value: unknown): value is ErrorEnvelope => {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.code === 'string' &&
    typeof v.message === 'string' &&
    typeof v.request_id === 'string' &&
    Array.isArray(v.field_errors)
  );
};

const parseRetryAfter = (headers: Headers | undefined): number | null => {
  const raw = headers?.get('Retry-After');
  if (!raw) return null;
  const seconds = Number(raw);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
};

/** Map a failed openapi-fetch result (status, parsed body, response headers) to ApiError. */
export const toApiError = (
  status: number,
  body: unknown,
  headers?: Headers,
  sentRequestId?: string | null,
): ApiError => {
  const retryAfterSeconds = parseRetryAfter(headers);
  if (isErrorEnvelope(body)) {
    return new ApiError({
      status,
      code: body.code,
      message: body.message,
      requestId: body.request_id,
      fieldErrors: body.field_errors,
      currentRevision: body.current_revision ?? null,
      retryAfterSeconds,
    });
  }
  return new ApiError({
    status,
    code: 'UNKNOWN',
    message:
      status === 0
        ? 'Network request failed'
        : `Request failed with status ${status}`,
    requestId: headers?.get('X-Request-Id') ?? sentRequestId ?? null,
    retryAfterSeconds,
  });
};

export const isApiError = (error: unknown): error is ApiError =>
  error instanceof ApiError;
