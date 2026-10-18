package com.beaconclimb.stream;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structural parse of {@code attempts.v1} bytes into an {@link AttemptEvent}. This is the BC-0303
 * stand-in for the shared schema validator (BC-0302): it enforces the closed {@code attributes}
 * allowlist (C-01, TM-11) and the required members, and rejects anything else with a {@link
 * ParseException}. Full JSON Schema validation replaces this once BC-0302 lands.
 */
public final class AttemptEventParser {

  /** The bytes are not a conforming attempts.v1 event; the message names the first problem. */
  public static final class ParseException extends RuntimeException {
    public ParseException(String message) {
      super(message);
    }

    public ParseException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  static final Set<String> TOP_LEVEL_KEYS =
      Set.of(
          "schema_version",
          "event_id",
          "event_type",
          "user_id",
          "attempt_id",
          "attempt_revision",
          "route_variant_id",
          "occurred_at",
          "ingested_at",
          "attributes",
          "trace_id",
          "producer");
  static final Set<String> REQUIRED_TOP_LEVEL_KEYS = TOP_LEVEL_KEYS;

  /** The closed allowlist of attempts.v1.schema.json plus the optional grade_numeric. */
  static final Set<String> ATTRIBUTE_KEYS =
      Set.of(
          "sent",
          "attempts_count",
          "completion_fraction",
          "perceived_effort",
          "status",
          "grade_numeric");

  static final Set<String> REQUIRED_ATTRIBUTE_KEYS = Set.of("sent", "attempts_count", "status");
  static final Set<String> EVENT_TYPES =
      Set.of("ATTEMPT_RECORDED", "ATTEMPT_CORRECTED", "ATTEMPT_RETRACTED");
  static final Set<String> STATUSES = Set.of("ACTIVE", "RETRACTED");

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private AttemptEventParser() {}

  public static AttemptEvent parse(byte[] raw) {
    JsonNode root;
    try {
      root = MAPPER.readTree(new String(raw, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new ParseException("not a JSON document: " + e.getMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new ParseException("event must be a JSON object");
    }
    rejectUnknownKeys(root, TOP_LEVEL_KEYS, "event");
    requireKeys(root, REQUIRED_TOP_LEVEL_KEYS, "event");
    if (!root.get("schema_version").isNumber() || root.get("schema_version").asInt() != 1) {
      throw new ParseException("schema_version must be 1");
    }
    JsonNode attributes = root.get("attributes");
    if (!attributes.isObject()) {
      throw new ParseException("attributes must be an object");
    }
    rejectUnknownKeys(attributes, ATTRIBUTE_KEYS, "attributes");
    requireKeys(attributes, REQUIRED_ATTRIBUTE_KEYS, "attributes");
    String eventType = text(root, "event_type");
    if (!EVENT_TYPES.contains(eventType)) {
      throw new ParseException("event_type '" + eventType + "' is not an attempts.v1 event type");
    }
    String status = text(attributes, "status");
    if (!STATUSES.contains(status)) {
      throw new ParseException("attributes.status '" + status + "' is not ACTIVE or RETRACTED");
    }
    JsonNode revision = root.get("attempt_revision");
    if (!revision.isIntegralNumber() || revision.asInt() < 1) {
      throw new ParseException("attempt_revision must be an integer >= 1");
    }
    JsonNode sent = attributes.get("sent");
    if (!sent.isBoolean()) {
      throw new ParseException("attributes.sent must be a boolean");
    }
    JsonNode grade = attributes.get("grade_numeric");
    if (grade != null && !grade.isNull() && !grade.isNumber()) {
      throw new ParseException("attributes.grade_numeric must be a number or null");
    }
    return new AttemptEvent(
        text(root, "event_id"),
        eventType,
        text(root, "user_id"),
        text(root, "attempt_id"),
        revision.asInt(),
        text(root, "route_variant_id"),
        instantMillis(text(root, "occurred_at")),
        sent.asBoolean(),
        status,
        grade == null || grade.isNull() ? null : grade.asDouble(),
        raw);
  }

  private static void rejectUnknownKeys(JsonNode node, Set<String> allowed, String where) {
    for (Map.Entry<String, JsonNode> entry : node.properties()) {
      if (!allowed.contains(entry.getKey())) {
        throw new ParseException("unknown " + where + " key '" + entry.getKey() + "'");
      }
    }
  }

  private static void requireKeys(JsonNode node, Set<String> required, String where) {
    for (String key : required) {
      if (node.get(key) == null) {
        throw new ParseException("missing " + where + " key '" + key + "'");
      }
    }
  }

  private static String text(JsonNode node, String key) {
    JsonNode value = node.get(key);
    if (value == null || !value.isString() || value.asString().isEmpty()) {
      throw new ParseException(key + " must be a non-empty string");
    }
    return value.asString();
  }

  private static long instantMillis(String text) {
    try {
      return Instant.parse(text).toEpochMilli();
    } catch (DateTimeParseException e) {
      throw new ParseException("occurred_at '" + text + "' is not an RFC 3339 date-time", e);
    }
  }
}
