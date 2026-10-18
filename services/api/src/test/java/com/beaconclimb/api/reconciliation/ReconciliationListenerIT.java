package com.beaconclimb.api.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.beaconclimb.api.outbox.KafkaTestContainer;
import com.beaconclimb.events.DlqHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * BC-0309 through the real listener container against the Testcontainers broker: a late attempts.v1
 * record lands as one reconciliation row with the parsed members and headers, a redelivery of the
 * same event_id is a counted no-op, an invalid payload goes to stream-dlq.v1 and is acknowledged
 * without a row, and a record whose user the database does not know is rejected the same way.
 */
class ReconciliationListenerIT extends ReconciliationTestSupport {

  @Test
  @DisplayName("a late attempts.v1 record becomes one open row; the same event_id again is a no-op")
  void lateRecordIsStoredOnceAndRedeliveryIsDuplicate() {
    Account account = registerWithConsent();
    LateAttempt late = writeLateAttempt(account, THREE_DAYS);
    Instant watermark = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    double receivedBefore = counter(ReconciliationMetrics.RECEIVED);
    double duplicatesBefore = counter(ReconciliationMetrics.DUPLICATES);

    RecordMetadata first =
        produceReconciliation(account.userId(), late.payload(), lateHeaders(watermark));
    await().atMost(WAIT).until(() -> committedOffset(first.partition()) >= first.offset() + 1);

    ReconciliationRow row = repository.findByEventId(late.eventId()).orElseThrow();
    assertThat(row.userId()).isEqualTo(account.userId());
    assertThat(row.attemptId()).isEqualTo(late.attemptId());
    assertThat(row.attemptRevision()).isEqualTo(1);
    assertThat(row.eventType()).isEqualTo("ATTEMPT_RECORDED");
    assertThat(row.occurredAt()).isEqualTo(late.occurredAt().truncatedTo(ChronoUnit.MILLIS));
    assertThat(row.sourceWatermark()).isEqualTo(watermark);
    assertThat(row.reason()).isEqualTo(ReconciliationEvent.REASON_LATE);
    assertThat(row.sourcePartition()).isEqualTo(first.partition());
    assertThat(row.sourceOffset()).isEqualTo(first.offset());
    assertThat(row.isOpen()).isTrue();
    assertThat(row.resolution()).isNull();
    assertThat(tree(row.originalJson()))
        .isEqualTo(tree(new String(late.payload(), StandardCharsets.UTF_8)));
    assertThat(counter(ReconciliationMetrics.RECEIVED)).isEqualTo(receivedBefore + 1);
    reconciliationMetrics.refreshOpenRows();
    assertThat(gauge(ReconciliationMetrics.OPEN_ROWS)).isGreaterThanOrEqualTo(1.0);

    RecordMetadata second =
        produceReconciliation(account.userId(), late.payload(), lateHeaders(watermark));
    assertThat(second.partition()).isEqualTo(first.partition());
    await().atMost(WAIT).until(() -> committedOffset(second.partition()) >= second.offset() + 1);

    assertThat(rowsOf(account.userId())).isEqualTo(1);
    assertThat(repository.findByEventId(late.eventId()).orElseThrow().sourceOffset())
        .isEqualTo(first.offset());
    assertThat(counter(ReconciliationMetrics.DUPLICATES)).isEqualTo(duplicatesBefore + 1);
    assertThat(counter(ReconciliationMetrics.RECEIVED)).isEqualTo(receivedBefore + 1);
  }

  @Test
  @DisplayName("a record without the stream headers is stored with reason OTHER and no watermark")
  void missingHeadersAreStoredAsOther() {
    Account account = registerWithConsent();
    LateAttempt late = writeLateAttempt(account, THREE_DAYS);

    RecordMetadata meta = produceReconciliation(account.userId(), late.payload(), Map.of());
    await().atMost(WAIT).until(() -> committedOffset(meta.partition()) >= meta.offset() + 1);

    ReconciliationRow row = repository.findByEventId(late.eventId()).orElseThrow();
    assertThat(row.reason()).isEqualTo(ReconciliationEvent.REASON_OTHER);
    assertThat(row.sourceWatermark()).isNull();
  }

  @Test
  @DisplayName("an invalid payload (notes, not JSON) -> DLQ consumer=reconciliation, acked, no row")
  void invalidPayloadGoesToDlqAndIsAcknowledged() {
    Account account = registerWithConsent();
    byte[] withNotes =
        ("{\"schema_version\":1,\"user_id\":\"" + account.userId() + "\",\"notes\":\"private\"}")
            .getBytes(StandardCharsets.UTF_8);
    byte[] notJson = ("not json at all " + account.userId()).getBytes(StandardCharsets.UTF_8);
    double notesBefore = counter(ReconciliationMetrics.INVALID, "error_code", "NOTES_PRESENT");
    double notJsonBefore = counter(ReconciliationMetrics.INVALID, "error_code", "NOT_JSON");

    RecordMetadata notesMeta =
        produceReconciliation(account.userId(), withNotes, lateHeaders(Instant.now()));
    RecordMetadata junkMeta =
        produceReconciliation(account.userId(), notJson, lateHeaders(Instant.now()));
    await()
        .atMost(WAIT)
        .until(() -> committedOffset(junkMeta.partition()) >= junkMeta.offset() + 1);

    List<JsonNode> notesDlq = dlqRecordsFor(withNotes);
    assertThat(notesDlq).hasSize(1);
    assertThat(notesDlq.getFirst().get("error_code").asString()).isEqualTo("NOTES_PRESENT");
    assertThat(notesDlq.getFirst().get("error_message").asString()).doesNotContain("private");
    assertThat(notesDlq.getFirst().get("original_topic").asString())
        .isEqualTo(KafkaTestContainer.RECONCILIATION_TOPIC);
    ConsumerRecord<String, byte[]> raw =
        dlqRecordOnTopic(notesDlq.getFirst().get("event_id").asString());
    assertThat(KafkaTestContainer.header(raw, DlqHeaders.CONSUMER))
        .isEqualTo(ReconciliationListener.DLQ_CONSUMER);
    assertThat(KafkaTestContainer.header(raw, DlqHeaders.ORIGINAL_OFFSET))
        .isEqualTo(Long.toString(notesMeta.offset()));
    assertThat(KafkaTestContainer.header(raw, ReconciliationEvent.HEADER_REASON))
        .isEqualTo(ReconciliationEvent.REASON_LATE);
    List<JsonNode> junkDlq = dlqRecordsFor(notJson);
    assertThat(junkDlq).hasSize(1);
    assertThat(junkDlq.getFirst().get("error_code").asString()).isEqualTo("NOT_JSON");
    assertThat(rowsOf(account.userId())).isZero();
    assertThat(counter(ReconciliationMetrics.INVALID, "error_code", "NOTES_PRESENT"))
        .isEqualTo(notesBefore + 1);
    assertThat(counter(ReconciliationMetrics.INVALID, "error_code", "NOT_JSON"))
        .isEqualTo(notJsonBefore + 1);
  }

  @Test
  @DisplayName("a valid payload for an unknown user -> SCHEMA_VIOLATION on the DLQ, acked, no row")
  void unknownUserGoesToDlq() {
    Account account = registerWithConsent();
    LateAttempt late = writeLateAttempt(account, THREE_DAYS);
    UUID unknownUser = UUID.randomUUID();
    byte[] payload =
        new String(late.payload(), StandardCharsets.UTF_8)
            .replace(account.userId().toString(), unknownUser.toString())
            .getBytes(StandardCharsets.UTF_8);

    RecordMetadata meta = produceReconciliation(unknownUser, payload, lateHeaders(Instant.now()));
    await().atMost(WAIT).until(() -> committedOffset(meta.partition()) >= meta.offset() + 1);

    List<JsonNode> dlq = dlqRecordsFor(payload);
    assertThat(dlq).hasSize(1);
    assertThat(dlq.getFirst().get("error_code").asString()).isEqualTo("SCHEMA_VIOLATION");
    assertThat(dlq.getFirst().get("error_message").asString())
        .contains("reconciliation_events_user_id_fkey");
    assertThat(rowsOf(unknownUser)).isZero();
    assertThat(repository.findByEventId(late.eventId())).isEmpty();
  }
}
