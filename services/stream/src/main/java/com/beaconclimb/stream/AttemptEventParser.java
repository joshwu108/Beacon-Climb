package com.beaconclimb.stream;

import com.beaconclimb.events.ErrorCode;
import com.beaconclimb.events.EventSchemas;
import com.beaconclimb.events.EventValidator;
import com.beaconclimb.events.ValidationResult.Invalid;
import com.beaconclimb.events.ValidationResult.Valid;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.JsonNode;

/**
 * {@code attempts.v1} bytes to a typed {@link AttemptEvent}. The decision whether the bytes conform
 * belongs to the shared {@link EventValidator} (BC-0302): this class only lifts the already
 * validated document into the record the operators work with, and reports a rejection as a {@link
 * ParseException} carrying the validator's {@link ErrorCode} and message.
 */
public final class AttemptEventParser {

  /** The bytes are not a conforming attempts.v1 event; the message names the first problem. */
  public static final class ParseException extends RuntimeException {
    private final ErrorCode errorCode;

    public ParseException(ErrorCode errorCode, String message) {
      super(message);
      this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
      return errorCode;
    }
  }

  /** One compiled schema set per JVM (per Flink user classloader); the validator is thread-safe. */
  private static final EventValidator VALIDATOR = new EventValidator();

  private AttemptEventParser() {}

  public static EventValidator validator() {
    return VALIDATOR;
  }

  public static AttemptEvent parse(byte[] raw) {
    return switch (VALIDATOR.validate(EventSchemas.ATTEMPTS, raw)) {
      case Valid valid -> fromValidated(valid.payload(), raw);
      case Invalid invalid -> throw new ParseException(invalid.errorCode(), invalid.message());
    };
  }

  /** Typed view of a document the validator already accepted. */
  static AttemptEvent fromValidated(JsonNode root, byte[] raw) {
    JsonNode attributes = root.get("attributes");
    JsonNode grade = attributes.get("grade_numeric");
    return new AttemptEvent(
        root.get("event_id").asString(),
        root.get("event_type").asString(),
        root.get("user_id").asString(),
        root.get("attempt_id").asString(),
        root.get("attempt_revision").asInt(),
        root.get("route_variant_id").asString(),
        instantMillis(root.get("occurred_at").asString()),
        attributes.get("sent").asBoolean(),
        attributes.get("status").asString(),
        grade == null || grade.isNull() ? null : grade.asDouble(),
        raw);
  }

  /**
   * The schema's {@code date-time} format is RFC 3339, which admits offsets and leap seconds that
   * {@code Instant.parse} does not; an offset is honoured, anything Java cannot represent is a
   * schema violation rather than a job failure.
   */
  private static long instantMillis(String text) {
    try {
      return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
          .toInstant()
          .toEpochMilli();
    } catch (DateTimeParseException e) {
      throw new ParseException(
          ErrorCode.SCHEMA_VIOLATION, "/occurred_at: not a representable RFC 3339 date-time");
    }
  }
}
