package com.beaconclimb.api.reconciliation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.databind.JsonNode;

/**
 * One stream-reconciliation.v1 record as the consumer stores it (BC-0309): the identifying members
 * of the ORIGINAL attempts.v1 payload the stream job forwarded, the job's headers ({@code
 * reason=LATE_BEYOND_WINDOW}, {@code watermark=<W>}; contracts/features/climber-features.v1.md
 * section 10 "Late events"), the payload text itself and the position it was consumed from.
 *
 * @param eventId the attempts.v1 {@code event_id} (the idempotency key of the table)
 * @param userId {@code user_id}
 * @param attemptId {@code attempt_id}
 * @param attemptRevision {@code attempt_revision}
 * @param eventType {@code event_type}
 * @param occurredAt {@code occurred_at}
 * @param sourceWatermark the {@code watermark} header, null when absent or unparseable
 * @param reason {@link #REASON_LATE} when the header says so, {@link #REASON_OTHER} otherwise
 * @param originalJson the payload bytes as UTF-8 text, stored verbatim as {@code original_json}
 * @param sourcePartition the stream-reconciliation.v1 partition
 * @param sourceOffset the offset within that partition
 */
public record ReconciliationEvent(
    UUID eventId,
    UUID userId,
    UUID attemptId,
    int attemptRevision,
    String eventType,
    Instant occurredAt,
    Instant sourceWatermark,
    String reason,
    String originalJson,
    int sourcePartition,
    long sourceOffset) {

  /** The stream job's header names and the one reason it emits (ClimberFeaturesFunction). */
  public static final String HEADER_REASON = "reason";

  public static final String HEADER_WATERMARK = "watermark";
  public static final String REASON_LATE = "LATE_BEYOND_WINDOW";
  public static final String REASON_OTHER = "OTHER";

  public ReconciliationEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(attemptId, "attemptId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(originalJson, "originalJson");
  }

  /** Builds the row from a validated attempts.v1 document and the record it was consumed in. */
  public static ReconciliationEvent from(ConsumerRecord<String, byte[]> record, JsonNode payload) {
    return new ReconciliationEvent(
        UUID.fromString(payload.get("event_id").asString()),
        UUID.fromString(payload.get("user_id").asString()),
        UUID.fromString(payload.get("attempt_id").asString()),
        payload.get("attempt_revision").asInt(),
        payload.get("event_type").asString(),
        Instant.parse(payload.get("occurred_at").asString()),
        watermarkOf(record),
        reasonOf(record),
        new String(record.value(), StandardCharsets.UTF_8),
        record.partition(),
        record.offset());
  }

  static String reasonOf(ConsumerRecord<?, ?> record) {
    return REASON_LATE.equals(header(record, HEADER_REASON)) ? REASON_LATE : REASON_OTHER;
  }

  static Instant watermarkOf(ConsumerRecord<?, ?> record) {
    String raw = header(record, HEADER_WATERMARK);
    if (raw == null) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String header(ConsumerRecord<?, ?> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null || header.value() == null
        ? null
        : new String(header.value(), StandardCharsets.UTF_8);
  }
}
