package com.beaconclimb.api.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Structured error body; mirrors {@code components.schemas.ErrorEnvelope} ({@code
 * additionalProperties: false}). {@code current_revision} is serialised only when present, which
 * the contract reserves for REVISION_CONFLICT (BC-0109). Never carries a stack trace or exception
 * text.
 */
public record ErrorEnvelope(
    ErrorCode code,
    String message,
    @JsonProperty("request_id") String requestId,
    @JsonProperty("field_errors") List<FieldError> fieldErrors,
    @JsonProperty("current_revision") @JsonInclude(JsonInclude.Include.NON_NULL)
        Integer currentRevision) {

  public ErrorEnvelope {
    fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
  }

  public static ErrorEnvelope of(
      ErrorCode code, String message, String requestId, List<FieldError> fieldErrors) {
    return new ErrorEnvelope(code, message, requestId, fieldErrors, null);
  }
}
