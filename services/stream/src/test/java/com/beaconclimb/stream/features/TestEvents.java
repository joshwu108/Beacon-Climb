package com.beaconclimb.stream.features;

import com.beaconclimb.events.Uuid7;
import com.beaconclimb.stream.AttemptEvent;
import com.beaconclimb.stream.AttemptEventParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds attempts.v1 events for the operator tests the way the api would publish them, and parses
 * them through the real {@link AttemptEventParser}, so every test input is a schema-valid event
 * whose original bytes travel with it (the late side output forwards those bytes).
 */
final class TestEvents {

  static final String USER_A = "018f0000-0000-7000-8000-0000000000a1";
  static final String USER_B = "018f0000-0000-7000-8000-0000000000b2";
  static final String USER_C = "018f0000-0000-7000-8000-0000000000c3";
  static final String VARIANT = "synthetic-route-001:40";

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private TestEvents() {}

  static AttemptEvent recorded(
      String userId, UUID attemptId, Instant at, String variant, boolean sent, Double grade) {
    return event(
        userId,
        attemptId,
        1,
        AttemptEvent.ATTEMPT_RECORDED,
        AttemptEvent.STATUS_ACTIVE,
        at,
        variant,
        sent,
        grade);
  }

  static AttemptEvent corrected(
      String userId,
      UUID attemptId,
      int revision,
      Instant at,
      String variant,
      boolean sent,
      Double grade) {
    return event(
        userId,
        attemptId,
        revision,
        AttemptEvent.ATTEMPT_CORRECTED,
        AttemptEvent.STATUS_ACTIVE,
        at,
        variant,
        sent,
        grade);
  }

  static AttemptEvent retracted(String userId, UUID attemptId, int revision, Instant at) {
    return event(
        userId,
        attemptId,
        revision,
        AttemptEvent.ATTEMPT_RETRACTED,
        AttemptEvent.STATUS_RETRACTED,
        at,
        VARIANT,
        false,
        null);
  }

  static AttemptEvent event(
      String userId,
      UUID attemptId,
      int revision,
      String eventType,
      String status,
      Instant at,
      String variant,
      boolean sent,
      Double grade) {
    return AttemptEventParser.parse(
        bytes(userId, attemptId, revision, eventType, status, at, variant, sent, grade));
  }

  static byte[] bytes(
      String userId,
      UUID attemptId,
      int revision,
      String eventType,
      String status,
      Instant at,
      String variant,
      boolean sent,
      Double grade) {
    ObjectNode event = JsonNodeFactory.instance.objectNode();
    event.put("schema_version", 1);
    event.put("event_id", Uuid7.next().toString());
    event.put("event_type", eventType);
    event.put("user_id", userId);
    event.put("attempt_id", attemptId.toString());
    event.put("attempt_revision", revision);
    event.put("route_variant_id", variant);
    event.put("occurred_at", at.toString());
    event.put("ingested_at", at.toString());
    ObjectNode attributes = event.putObject("attributes");
    attributes.put("sent", sent);
    attributes.put("attempts_count", 1);
    attributes.put("status", status);
    if (grade == null) {
      attributes.putNull("grade_numeric");
    } else {
      attributes.put("grade_numeric", grade);
    }
    event.put("trace_id", "synthetic-trace-" + attemptId);
    event.put("producer", "api");
    return MAPPER.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
  }

  /** The deterministic id {@code 018f0000-0000-7000-8000-<n as 12 hex digits>}. */
  static UUID id(long n) {
    return UUID.fromString(String.format("018f0000-0000-7000-8000-%012x", n));
  }
}
