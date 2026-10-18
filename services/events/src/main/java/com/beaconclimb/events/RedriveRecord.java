package com.beaconclimb.events;

import com.beaconclimb.events.ValidationResult.Invalid;
import com.beaconclimb.events.ValidationResult.Valid;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * What a redrive publishes for one DLQ record (ADR-0004: original bytes preserved, attempt count
 * incremented): the decoded ORIGINAL value, the original key, and the DLQ record's headers with
 * {@code dlq.attempt} set to the failed attempt plus one and {@code dlq.redriven_from} set to the
 * DLQ record's {@code event_id}. The failing consumer reads {@code dlq.attempt} back through {@link
 * DlqHeaders#attemptOf(Map)}, so a second failure lands in the DLQ as attempt 2.
 *
 * @param topic the original topic
 * @param key the original key, null when the original key was null (stored as "")
 * @param value the original bytes, byte for byte
 * @param headers the headers to send
 * @param dlqEventId the DLQ record this came from
 * @param attempt the attempt number of the republished record (previous + 1)
 */
public record RedriveRecord(
    String topic,
    String key,
    byte[] value,
    Map<String, String> headers,
    String dlqEventId,
    int attempt) {

  public RedriveRecord {
    value = value.clone();
    headers = Map.copyOf(headers);
  }

  @Override
  public byte[] value() {
    return value.clone();
  }

  /** The DLQ record is not a conforming stream-dlq.v1 record; redriving it would be a guess. */
  public static final class InvalidDlqRecordException extends RuntimeException {
    private final ErrorCode errorCode;

    InvalidDlqRecordException(ErrorCode errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
      return errorCode;
    }
  }

  /**
   * @param validator judges the DLQ value against stream-dlq.v1 first
   * @param dlqValue the DLQ record's Kafka value
   * @param dlqHeaders the DLQ record's Kafka headers (may be empty)
   * @throws InvalidDlqRecordException when the value is not a conforming DLQ record or its base64
   *     does not decode
   */
  public static RedriveRecord from(
      EventValidator validator, byte[] dlqValue, Map<String, String> dlqHeaders) {
    JsonNode dlq =
        switch (validator.validate(EventSchemas.DLQ, dlqValue)) {
          case Valid valid -> valid.payload();
          case Invalid invalid ->
              throw new InvalidDlqRecordException(invalid.errorCode(), invalid.message());
        };
    byte[] original;
    try {
      original = Base64.getDecoder().decode(dlq.get("original_value_base64").asString());
    } catch (IllegalArgumentException e) {
      throw new InvalidDlqRecordException(
          ErrorCode.SCHEMA_VIOLATION, "original_value_base64 is not base64: " + e.getMessage());
    }
    String dlqEventId = dlq.get("event_id").asString();
    int nextAttempt = dlq.get("attempt").asInt() + 1;
    Map<String, String> headers = new LinkedHashMap<>();
    if (dlqHeaders != null) {
      headers.putAll(dlqHeaders);
    }
    headers.put(DlqHeaders.ORIGINAL_TOPIC, dlq.get("original_topic").asString());
    headers.put(DlqHeaders.ERROR_CODE, dlq.get("error_code").asString());
    headers.put(DlqHeaders.ATTEMPT, Integer.toString(nextAttempt));
    headers.put(DlqHeaders.REDRIVEN_FROM, dlqEventId);
    String key = dlq.get("original_key").asString();
    return new RedriveRecord(
        dlq.get("original_topic").asString(),
        key.isEmpty() ? null : key,
        original,
        headers,
        dlqEventId,
        nextAttempt);
  }
}
