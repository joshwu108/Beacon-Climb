package com.beaconclimb.api.attempts;

import com.beaconclimb.api.catalog.api.RouteVariantSummaryView;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code attempts} row at its latest revision joined with its route summary. Instants are
 * millisecond precision (the ingest truncation, see {@link AttemptService}). {@code
 * responseBodyJson} is the persisted 201 body used for idempotent replays; {@code
 * requestFingerprint} is the JCS fingerprint of the creating request.
 */
public record AttemptRow(
    UUID attemptId,
    UUID userId,
    UUID variantId,
    String routeVariantId,
    Instant occurredAt,
    Instant receivedAt,
    int attemptsCount,
    boolean sent,
    Double completionFraction,
    Integer perceivedEffort,
    String notes,
    int revision,
    AttemptStatus status,
    String requestFingerprint,
    String responseBodyJson,
    RouteVariantSummaryView route) {

  /** Notes are owner-only; a row must never render them into a log line (TM-10). */
  @Override
  public String toString() {
    return "AttemptRow[attemptId=" + attemptId + ", revision=" + revision + "]";
  }
}
