package com.beaconclimb.api.platform.web;

import org.springframework.http.HttpStatus;

/**
 * Maps an HTTP status that did not originate from an {@link ApiException} (container errors,
 * Spring's own 4xx, {@code ResponseStatusException}) to the closest contract {@link ErrorCode} and
 * the status actually sent. 405 becomes 404 so reserved paths do not enumerate their methods; 415
 * becomes 422 because the contract declares no 415; every other unmapped 4xx keeps its status with
 * VALIDATION_FAILED and every unmapped 5xx is 500 INTERNAL_ERROR.
 */
final class StatusCodes {

  record Mapping(HttpStatus status, ErrorCode code, String message) {}

  private StatusCodes() {}

  static Mapping forStatus(HttpStatus status) {
    return switch (status) {
      case UNAUTHORIZED ->
          new Mapping(status, ErrorCode.UNAUTHENTICATED, "Missing or invalid bearer token");
      case FORBIDDEN -> new Mapping(status, ErrorCode.FORBIDDEN, "Forbidden");
      case NOT_FOUND, METHOD_NOT_ALLOWED ->
          new Mapping(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "No such resource");
      case CONTENT_TOO_LARGE ->
          new Mapping(status, ErrorCode.PAYLOAD_TOO_LARGE, "Request body is too large");
      case UNSUPPORTED_MEDIA_TYPE ->
          new Mapping(
              HttpStatus.UNPROCESSABLE_CONTENT,
              ErrorCode.VALIDATION_FAILED,
              "Request failed validation");
      case TOO_MANY_REQUESTS -> new Mapping(status, ErrorCode.RATE_LIMITED, "Too many requests");
      case NOT_IMPLEMENTED -> new Mapping(status, ErrorCode.NOT_IMPLEMENTED, "Not implemented");
      case SERVICE_UNAVAILABLE ->
          new Mapping(status, ErrorCode.SERVICE_UNAVAILABLE, "Service unavailable");
      default ->
          status.is4xxClientError()
              ? new Mapping(status, ErrorCode.VALIDATION_FAILED, "Request failed validation")
              : new Mapping(
                  HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Internal error");
    };
  }
}
