package com.beaconclimb.events;

import tools.jackson.databind.JsonNode;

/** The outcome of {@link EventValidator#validate(String, byte[])}. */
public sealed interface ValidationResult {

  /** The bytes conform; {@code payload} is the parsed document. */
  record Valid(JsonNode payload) implements ValidationResult {}

  /**
   * The bytes do not conform. {@code message} names the first problems without quoting the rejected
   * values, so it is safe to log and to store in the DLQ record.
   */
  record Invalid(ErrorCode errorCode, String message) implements ValidationResult {}
}
