package com.beaconclimb.api.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * One locked {@code attempt_event_outbox} row. {@code userId} is {@code payload_json->>'user_id'}
 * (the table has no user column; the payload is the validated attempts.v1 event) and {@code
 * payloadJson} is the jsonb text rendering Postgres returns, sent to Kafka byte for byte without
 * being re-serialised by the application.
 */
public record OutboxRow(
    UUID eventId,
    UUID aggregateId,
    int aggregateRevision,
    String eventType,
    int schemaVersion,
    String userId,
    String payloadJson,
    Instant createdAt) {

  @Override
  public String toString() {
    return "OutboxRow[eventId=" + eventId + ", revision=" + aggregateRevision + "]";
  }
}
