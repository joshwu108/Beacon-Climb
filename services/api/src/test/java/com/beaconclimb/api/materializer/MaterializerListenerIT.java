package com.beaconclimb.api.materializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.materializer.MaterializerService.Outcome;
import com.beaconclimb.api.outbox.KafkaTestContainer;
import com.beaconclimb.events.DlqHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * BC-0305 through the real listener container against the Testcontainers broker: physical
 * redelivery at a new offset, a conflict surfacing on stream-dlq.v1, invalid bytes acknowledged
 * after the DLQ write, a crash after the commit and before the acknowledgement, and a row the
 * database refuses.
 */
class MaterializerListenerIT extends MaterializerTestSupport {

  @BeforeEach
  void containerRuns() {
    ensureRunning();
  }

  @AfterEach
  void restartIfStopped() {
    ensureRunning();
  }

  @Test
  @DisplayName(
      "(a) a second physical copy at a new offset is applied; still one row, same checksum")
  void physicalRedeliveryAtNewOffset() {
    Account account = registerWithConsent();
    byte[] bytes = featureRecord(account.userId(), CHECKSUM_A, aggregates(1));
    double appliedBefore = counter(MaterializerMetrics.APPLIED);

    RecordMetadata first = produce(account.userId(), bytes);
    RecordMetadata second = produce(account.userId(), bytes);
    assertThat(second.partition()).isEqualTo(first.partition());
    assertThat(second.offset()).isGreaterThan(first.offset());

    await().atMost(WAIT).until(() -> committedOffset(first.partition()) >= second.offset() + 1);
    assertThat(counter(MaterializerMetrics.APPLIED)).isEqualTo(appliedBefore + 2);
    assertThat(streamRows(account.userId())).isEqualTo(1);
    FeatureSnapshot row = streamRow(account.userId()).orElseThrow();
    assertThat(row.provenanceChecksum()).isEqualTo(CHECKSUM_A);
    assertThat(row.sourcePartition()).isEqualTo(first.partition());
    assertThat(row.sourceOffset()).isEqualTo(second.offset());
    assertThat(row.aggregates()).isEqualTo(aggregates(1));
  }

  @Test
  @DisplayName("(d) equal offset + different checksum -> CHECKSUM_CONFLICT on stream-dlq.v1, acked")
  void checksumConflictLandsInDlq() {
    Account account = registerWithConsent();
    byte[] onTopic = featureRecord(account.userId(), CHECKSUM_A, aggregates(1));
    double conflictsBefore = counter(MaterializerMetrics.CONFLICT, "reason", "checksum");
    container.stop();
    RecordMetadata meta = produce(account.userId(), onTopic);
    // The row already holds this exact position with another checksum when the listener reads it.
    byte[] stored = featureRecord(account.userId(), CHECKSUM_B, aggregates(2));
    assertThat(materializer.apply(meta.partition(), meta.offset(), event(stored)).outcome())
        .isEqualTo(Outcome.APPLIED);
    container.start();

    await().atMost(WAIT).until(() -> committedOffset(meta.partition()) >= meta.offset() + 1);

    List<JsonNode> dlq = dlqRecordsFor(onTopic);
    assertThat(dlq).hasSize(1);
    JsonNode record = dlq.getFirst();
    assertThat(record.get("error_code").asString()).isEqualTo("CHECKSUM_CONFLICT");
    assertThat(record.get("error_message").asString())
        .contains("CHECKSUM_CONFLICT")
        .contains("offset=" + meta.offset())
        .contains(CHECKSUM_A)
        .contains(CHECKSUM_B);
    assertThat(record.get("original_topic").asString())
        .isEqualTo(KafkaTestContainer.FEATURES_TOPIC);
    assertThat(record.get("original_key").asString()).isEqualTo(account.userId().toString());
    assertThat(record.get("attempt").asInt()).isEqualTo(1);
    ConsumerRecord<String, byte[]> raw = dlqRecordOnTopic(record.get("event_id").asString());
    assertThat(KafkaTestContainer.header(raw, DlqHeaders.ORIGINAL_PARTITION))
        .isEqualTo(Integer.toString(meta.partition()));
    assertThat(KafkaTestContainer.header(raw, DlqHeaders.ORIGINAL_OFFSET))
        .isEqualTo(Long.toString(meta.offset()));
    assertThat(KafkaTestContainer.header(raw, DlqHeaders.CONSUMER)).isEqualTo("api");
    FeatureSnapshot row = streamRow(account.userId()).orElseThrow();
    assertThat(row.provenanceChecksum()).isEqualTo(CHECKSUM_B);
    assertThat(row.sourceOffset()).isEqualTo(meta.offset());
    assertThat(counter(MaterializerMetrics.CONFLICT, "reason", "checksum"))
        .isEqualTo(conflictsBefore + 1);
  }

  @Test
  @DisplayName("(e) invalid bytes (notes, not JSON) -> DLQ via the shared validator, acked, no row")
  void invalidBytesGoToDlqAndAreAcknowledged() {
    Account account = registerWithConsent();
    byte[] withNotes =
        ("{\"schema_version\":1,\"user_id\":\"" + account.userId() + "\",\"notes\":\"private\"}")
            .getBytes(StandardCharsets.UTF_8);
    byte[] notJson = "not json at all".getBytes(StandardCharsets.UTF_8);
    double notesBefore = counter(MaterializerMetrics.INVALID, "error_code", "NOTES_PRESENT");
    double notJsonBefore = counter(MaterializerMetrics.INVALID, "error_code", "NOT_JSON");

    RecordMetadata notesMeta = produce(account.userId(), withNotes);
    RecordMetadata junkMeta = produce(account.userId(), notJson);

    await()
        .atMost(WAIT)
        .until(() -> committedOffset(junkMeta.partition()) >= junkMeta.offset() + 1);
    List<JsonNode> notesDlq = dlqRecordsFor(withNotes);
    assertThat(notesDlq).hasSize(1);
    assertThat(notesDlq.getFirst().get("error_code").asString()).isEqualTo("NOTES_PRESENT");
    assertThat(notesDlq.getFirst().get("error_message").asString()).doesNotContain("private");
    ConsumerRecord<String, byte[]> raw =
        dlqRecordOnTopic(notesDlq.getFirst().get("event_id").asString());
    assertThat(KafkaTestContainer.header(raw, DlqHeaders.ORIGINAL_OFFSET))
        .isEqualTo(Long.toString(notesMeta.offset()));
    List<JsonNode> junkDlq = dlqRecordsFor(notJson);
    assertThat(junkDlq).hasSize(1);
    assertThat(junkDlq.getFirst().get("error_code").asString()).isEqualTo("NOT_JSON");
    assertThat(streamRow(account.userId())).isEmpty();
    assertThat(counter(MaterializerMetrics.INVALID, "error_code", "NOTES_PRESENT"))
        .isEqualTo(notesBefore + 1);
    assertThat(counter(MaterializerMetrics.INVALID, "error_code", "NOT_JSON"))
        .isEqualTo(notJsonBefore + 1);
  }

  @Test
  @DisplayName(
      "(f) crash after commit, before ack: redelivered, detected as duplicate, offset moves")
  void crashAfterCommitBeforeAckRecoversAsDuplicate() throws InterruptedException {
    Account account = registerWithConsent();
    byte[] bytes = featureRecord(account.userId(), CHECKSUM_A, aggregates(1));
    double duplicatesBefore = counter(MaterializerMetrics.SKIPPED, "reason", "duplicate");
    double errorsBefore = counter(MaterializerMetrics.ERRORS);
    hook.arm();

    RecordMetadata meta = produce(account.userId(), bytes);
    assertThat(hook.awaitFired(WAIT)).as("hook fired after the commit").isTrue();
    assertThat(hook.lastResult().outcome()).isEqualTo(Outcome.APPLIED);
    container.stop();

    // Committed in Postgres, not acknowledged in Kafka.
    FeatureSnapshot committed = streamRow(account.userId()).orElseThrow();
    assertThat(committed.provenanceChecksum()).isEqualTo(CHECKSUM_A);
    assertThat(committed.sourceOffset()).isEqualTo(meta.offset());
    assertThat(committedOffset(meta.partition())).isLessThan(meta.offset() + 1);
    assertThat(counter(MaterializerMetrics.ERRORS)).isEqualTo(errorsBefore + 1);

    container.start();

    await().atMost(WAIT).until(() -> committedOffset(meta.partition()) >= meta.offset() + 1);
    assertThat(counter(MaterializerMetrics.SKIPPED, "reason", "duplicate"))
        .isEqualTo(duplicatesBefore + 1);
    assertThat(streamRows(account.userId())).isEqualTo(1);
    assertThat(streamRow(account.userId())).contains(committed);
  }

  @Test
  @DisplayName("a row the database refuses (unknown user) -> SCHEMA_VIOLATION on the DLQ, acked")
  void databaseRejectionGoesToDlq() {
    UUID unknownUser = UUID.randomUUID();
    byte[] bytes = featureRecord(unknownUser, CHECKSUM_A, aggregates(1));

    RecordMetadata meta = produce(unknownUser, bytes);

    await().atMost(WAIT).until(() -> committedOffset(meta.partition()) >= meta.offset() + 1);
    List<JsonNode> dlq = dlqRecordsFor(bytes);
    assertThat(dlq).hasSize(1);
    assertThat(dlq.getFirst().get("error_code").asString()).isEqualTo("SCHEMA_VIOLATION");
    assertThat(dlq.getFirst().get("error_message").asString())
        .contains("user_feature_snapshots_user_id_fkey");
    assertThat(streamRow(unknownUser)).isEmpty();
  }

  private void ensureRunning() {
    if (!container.isRunning()) {
      container.start();
    }
    await().atMost(WAIT).until(container::isRunning);
  }
}
