package com.beaconclimb.api.attempts;

import com.beaconclimb.api.platform.web.Timestamps;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * The logical {@code Attempt} body without the route summary: what {@code
 * attempts.response_body_json} and {@code attempt_revisions.snapshot_json} store (ADR-0002). The
 * route summary is joined on read so a later catalog correction is reflected everywhere.
 */
public record AttemptBody(
    @JsonProperty("attempt_id") UUID attemptId,
    @JsonProperty("route_variant_id") String routeVariantId,
    @JsonProperty("occurred_at") String occurredAt,
    @JsonProperty("received_at") String receivedAt,
    @JsonProperty("attempts_count") int attemptsCount,
    boolean sent,
    @JsonProperty("completion_fraction") Double completionFraction,
    @JsonProperty("perceived_effort") Integer perceivedEffort,
    String notes,
    int revision,
    AttemptStatus status) {

  public static AttemptBody of(AttemptRow row) {
    return new AttemptBody(
        row.attemptId(),
        row.routeVariantId(),
        Timestamps.format(row.occurredAt()),
        Timestamps.format(row.receivedAt()),
        row.attemptsCount(),
        row.sent(),
        row.completionFraction(),
        row.perceivedEffort(),
        row.notes(),
        row.revision(),
        row.status());
  }

  /** Spring MVC logs response values at DEBUG; notes never reach the log (TM-10, TM-16). */
  @Override
  public String toString() {
    return "AttemptBody[attemptId=" + attemptId + ", revision=" + revision + "]";
  }
}
