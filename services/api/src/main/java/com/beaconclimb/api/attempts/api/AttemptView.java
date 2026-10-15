package com.beaconclimb.api.attempts.api;

import com.beaconclimb.api.attempts.AttemptBody;
import com.beaconclimb.api.attempts.AttemptStatus;
import com.beaconclimb.api.catalog.api.RouteVariantSummaryView;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * {@code components.schemas.Attempt} ({@code additionalProperties: false}): the logical body plus
 * the current route summary. Nullable members are always serialised (the schema requires them).
 */
public record AttemptView(
    @JsonProperty("attempt_id") UUID attemptId,
    @JsonProperty("route_variant_id") String routeVariantId,
    RouteVariantSummaryView route,
    @JsonProperty("occurred_at") String occurredAt,
    @JsonProperty("received_at") String receivedAt,
    @JsonProperty("attempts_count") int attemptsCount,
    boolean sent,
    @JsonProperty("completion_fraction") Double completionFraction,
    @JsonProperty("perceived_effort") Integer perceivedEffort,
    String notes,
    int revision,
    AttemptStatus status) {

  public static AttemptView of(AttemptBody body, RouteVariantSummaryView route) {
    return new AttemptView(
        body.attemptId(),
        body.routeVariantId(),
        route,
        body.occurredAt(),
        body.receivedAt(),
        body.attemptsCount(),
        body.sent(),
        body.completionFraction(),
        body.perceivedEffort(),
        body.notes(),
        body.revision(),
        body.status());
  }

  /** Spring MVC logs response values at DEBUG; notes never reach the log (TM-10, TM-16). */
  @Override
  public String toString() {
    return "AttemptView[attemptId=" + attemptId + ", revision=" + revision + "]";
  }
}
