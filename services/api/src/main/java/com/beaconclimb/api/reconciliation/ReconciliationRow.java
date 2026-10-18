package com.beaconclimb.api.reconciliation;

import java.time.Instant;
import java.util.UUID;

/** One stored {@code reconciliation_events} row (read side; the write side is the event). */
public record ReconciliationRow(
    UUID id,
    UUID userId,
    UUID attemptId,
    int attemptRevision,
    UUID eventId,
    String eventType,
    Instant occurredAt,
    Instant sourceWatermark,
    String reason,
    String originalJson,
    Integer sourcePartition,
    Long sourceOffset,
    Instant receivedAt,
    Instant resolvedAt,
    Resolution resolution,
    Instant resolvedCutoffW,
    String resolvedChecksum) {

  public boolean isOpen() {
    return resolvedAt == null;
  }
}
