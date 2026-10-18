package com.beaconclimb.api.attempts;

import com.beaconclimb.api.platform.web.Timestamps;
import com.networknt.schema.Error;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds {@code attempts.v1} outbox payloads from an explicit field list (TM-11): the envelope
 * members the schema requires plus the {@code attributes} allowlist (sent, attempts_count, status,
 * grade_numeric, optional completion_fraction and perceived_effort). {@code grade_numeric}
 * (BC-0301, additive within v1) is the variant's current grade or an explicit null when ungraded;
 * consumers treat null and absence alike as UNGRADED. {@code notes} is never read here. Every
 * payload is validated against the bundled schema before it is returned; a violation is a server
 * bug (500), never a client error, so it is thrown as {@link IllegalStateException}.
 */
@Component
public class AttemptEvents {

  public static final String PRODUCER = "api";
  private static final int MAX_ERRORS_IN_MESSAGE = 5;

  private final AttemptEventSchema schema;
  private final JsonMapper json;

  public AttemptEvents(AttemptEventSchema schema, JsonMapper json) {
    this.schema = schema;
    this.json = json;
  }

  /** The validated payload JSON of {@code change} applied to {@code row} at its revision. */
  public String payload(
      AttemptChange change, UUID eventId, AttemptRow row, Instant ingestedAt, String traceId) {
    ObjectNode event = json.createObjectNode();
    event.put("schema_version", AttemptAuditRepository.SCHEMA_VERSION);
    event.put("event_id", eventId.toString());
    event.put("event_type", change.eventType());
    event.put("user_id", row.userId().toString());
    event.put("attempt_id", row.attemptId().toString());
    event.put("attempt_revision", row.revision());
    event.put("route_variant_id", row.routeVariantId());
    event.put("occurred_at", Timestamps.format(row.occurredAt()));
    event.put("ingested_at", Timestamps.format(ingestedAt));
    ObjectNode attributes = event.putObject("attributes");
    attributes.put("sent", row.sent());
    attributes.put("attempts_count", row.attemptsCount());
    attributes.put("status", row.status().name());
    if (row.completionFraction() != null) {
      attributes.put("completion_fraction", row.completionFraction());
    }
    if (row.perceivedEffort() != null) {
      attributes.put("perceived_effort", row.perceivedEffort());
    }
    // BC-0301: the variant's grade_numeric as read with the row (route_variants join on patch,
    // the visible-variant lookup on create); explicit null when the variant is ungraded.
    attributes.put("grade_numeric", row.route().gradeNumeric());
    event.put("trace_id", traceId);
    event.put("producer", PRODUCER);
    List<Error> errors = schema.validate(event);
    if (!errors.isEmpty()) {
      throw new IllegalStateException(
          "attempts.v1 payload for attempt "
              + row.attemptId()
              + " revision "
              + row.revision()
              + " violates the event contract: "
              + errors.stream()
                  .limit(MAX_ERRORS_IN_MESSAGE)
                  .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
                  .collect(Collectors.joining("; ")));
    }
    return json.writeValueAsString(event);
  }
}
