package com.beaconclimb.api.attempts;

import com.beaconclimb.api.catalog.RouteVariantId;
import java.time.Instant;

/**
 * A shape-valid {@code AttemptCreate} (contract 1.1.0): the public route variant id parsed into its
 * parts, {@code occurred_at} as a UTC instant at millisecond precision and the optional members as
 * nulls when absent. Produced only by {@link AttemptCreateValidator}.
 */
public record AttemptCreate(
    RouteVariantId routeVariantId,
    String routeVariantPublicId,
    Instant occurredAt,
    int attemptsCount,
    boolean sent,
    Double completionFraction,
    Integer perceivedEffort,
    String notes) {

  @Override
  public String toString() {
    return "AttemptCreate[routeVariantPublicId=" + routeVariantPublicId + "]";
  }
}
