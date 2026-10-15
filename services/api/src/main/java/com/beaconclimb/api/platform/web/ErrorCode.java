package com.beaconclimb.api.platform.web;

/**
 * Closed set of top-level error codes; mirrors {@code components.schemas.ErrorCode} in
 * contracts/openapi.yaml 1.1.0 exactly. Adding a value here without a contract change breaks the
 * contract-conformance tests on purpose.
 */
public enum ErrorCode {
  VALIDATION_FAILED,
  UNKNOWN_FIELD,
  OCCURRED_AT_IN_FUTURE,
  INVALID_CURSOR,
  UNKNOWN_BOARD,
  UNSUPPORTED_ANGLE,
  UNKNOWN_ROUTE_VARIANT,
  USERNAME_TAKEN,
  INVALID_CREDENTIALS,
  UNAUTHENTICATED,
  CONSENT_REQUIRED,
  FORBIDDEN,
  NOT_FOUND,
  IDEMPOTENCY_CONFLICT,
  REVISION_CONFLICT,
  PAYLOAD_TOO_LARGE,
  RATE_LIMITED,
  SERVICE_UNAVAILABLE,
  NOT_IMPLEMENTED,
  INTERNAL_ERROR,
  REPLAY_UNAVAILABLE_ERASED
}
