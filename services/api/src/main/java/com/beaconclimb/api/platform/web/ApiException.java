package com.beaconclimb.api.platform.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Domain error that {@link ApiExceptionHandler} renders as an {@link ErrorEnvelope}. The message is
 * client-facing and must never contain secrets, SQL or exception text. {@code headers} carries
 * contract-mandated response headers such as {@code Retry-After} for RATE_LIMITED.
 */
public class ApiException extends RuntimeException {

  private final ErrorCode code;
  private final HttpStatus status;
  private final List<FieldError> fieldErrors;
  private final Map<String, String> headers;
  private final Integer currentRevision;

  public ApiException(ErrorCode code, HttpStatus status, String message) {
    this(code, status, message, List.of(), Map.of());
  }

  public ApiException(
      ErrorCode code, HttpStatus status, String message, List<FieldError> fieldErrors) {
    this(code, status, message, fieldErrors, Map.of());
  }

  public ApiException(
      ErrorCode code,
      HttpStatus status,
      String message,
      List<FieldError> fieldErrors,
      Map<String, String> headers) {
    this(code, status, message, fieldErrors, headers, null);
  }

  private ApiException(
      ErrorCode code,
      HttpStatus status,
      String message,
      List<FieldError> fieldErrors,
      Map<String, String> headers,
      Integer currentRevision) {
    super(message, null, false, false);
    this.code = code;
    this.status = status;
    this.fieldErrors = List.copyOf(fieldErrors);
    this.headers = Map.copyOf(headers);
    this.currentRevision = currentRevision;
  }

  public static ApiException validationFailed(List<FieldError> fieldErrors) {
    return new ApiException(
        ErrorCode.VALIDATION_FAILED,
        HttpStatus.UNPROCESSABLE_CONTENT,
        "Request failed validation",
        fieldErrors);
  }

  /**
   * 409 REVISION_CONFLICT (ADR-0002, BC-0109): the envelope carries {@code current_revision} so the
   * client can re-read and retry with a fresh {@code If-Match}.
   */
  public static ApiException revisionConflict(int currentRevision) {
    return new ApiException(
        ErrorCode.REVISION_CONFLICT,
        HttpStatus.CONFLICT,
        "Attempt revision changed",
        List.of(),
        Map.of(),
        currentRevision);
  }

  public static ApiException unauthenticated() {
    return new ApiException(
        ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token");
  }

  public static ApiException rateLimited(long retryAfterSeconds) {
    return new ApiException(
        ErrorCode.RATE_LIMITED,
        HttpStatus.TOO_MANY_REQUESTS,
        "Too many login attempts; retry later",
        List.of(),
        Map.of("Retry-After", Long.toString(Math.max(1, retryAfterSeconds))));
  }

  public ErrorCode code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public List<FieldError> fieldErrors() {
    return fieldErrors;
  }

  public Map<String, String> headers() {
    return headers;
  }

  /** Present only for REVISION_CONFLICT; null otherwise. */
  public Integer currentRevision() {
    return currentRevision;
  }
}
