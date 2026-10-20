/**
 * Typed API failures. The ApiError class and the envelope mapping are apps/web's
 * (src/api/errors.ts), shared as source so both clients read the contract's ErrorEnvelope
 * the same way.
 */
export {
  ApiError,
  isApiError,
  isErrorEnvelope,
  toApiError,
  type ErrorCode,
  type ErrorEnvelope,
  type FieldError,
} from '@web/api/errors';
