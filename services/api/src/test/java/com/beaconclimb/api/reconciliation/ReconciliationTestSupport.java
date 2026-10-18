package com.beaconclimb.api.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.outbox.KafkaTestContainer;
import com.beaconclimb.api.outbox.StreamTestSupport;
import com.beaconclimb.events.EventSchemas;
import com.beaconclimb.events.EventValidator;
import com.beaconclimb.events.ValidationResult;
import com.beaconclimb.events.ValidationResult.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stream harness plus the reconciliation consumer (BC-0309): builds the ORIGINAL attempts.v1
 * bytes of a real attempt (written through the HTTP path, read back from the outbox), produces them
 * to stream-reconciliation.v1 with the stream job's headers, and reads the table, the committed
 * group offset and the DLQ back.
 */
public abstract class ReconciliationTestSupport extends StreamTestSupport {

  protected static final Duration WAIT = Duration.ofSeconds(30);
  protected static final Duration THREE_DAYS = Duration.ofDays(3);
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** A written attempt and the attempts.v1 payload the outbox holds for its first revision. */
  protected record LateAttempt(UUID attemptId, UUID eventId, Instant occurredAt, byte[] payload) {}

  @Autowired protected ReconciliationRepository repository;
  @Autowired protected ReconciliationMetrics reconciliationMetrics;
  @Autowired protected EventValidator validator;
  @Autowired protected KafkaTemplate<String, byte[]> kafka;

  @Autowired
  @Qualifier("reconciliationContainer")
  protected ConcurrentMessageListenerContainer<String, byte[]> container;

  /** Writes an attempt {@code age} in the past through the real HTTP path. */
  protected LateAttempt writeLateAttempt(Account account, Duration age) {
    Instant occurredAt = Instant.now().minus(age).minusSeconds(60);
    ResponseEntity<String> created =
        postAttempt(
            account.token(),
            UUID.randomUUID().toString(),
            attemptBody(visibleRouteId(), occurredAt));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID attemptId = UUID.fromString(tree(created.getBody()).get("attempt_id").asString());
    Map<String, Object> outbox = outboxRows(attemptId).getFirst();
    byte[] payload = outbox.get("payload_json").toString().getBytes(StandardCharsets.UTF_8);
    assertThat(validator.validate(EventSchemas.ATTEMPTS, payload)).isInstanceOf(Valid.class);
    return new LateAttempt(
        attemptId, UUID.fromString(outbox.get("event_id").toString()), occurredAt, payload);
  }

  /** Produces to stream-reconciliation.v1 keyed by user id with the given headers. */
  protected RecordMetadata produceReconciliation(
      UUID userId, byte[] bytes, Map<String, String> headers) {
    ProducerRecord<String, byte[]> record =
        new ProducerRecord<>(KafkaTestContainer.RECONCILIATION_TOPIC, userId.toString(), bytes);
    headers.forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
    try {
      return kafka.send(record).get(WAIT.toSeconds(), TimeUnit.SECONDS).getRecordMetadata();
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("produce failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  protected static Map<String, String> lateHeaders(Instant watermark) {
    return Map.of(
        ReconciliationEvent.HEADER_REASON,
        ReconciliationEvent.REASON_LATE,
        ReconciliationEvent.HEADER_WATERMARK,
        watermark.toString());
  }

  protected int rowsOf(UUID userId) {
    return jdbc.sql("SELECT count(*) FROM reconciliation_events WHERE user_id = :id")
        .param("id", userId)
        .query(Integer.class)
        .single();
  }

  /** The committed offset of the reconciliation group on {@code partition}, or -1 when none. */
  protected long committedOffset(int partition) {
    try (Admin admin =
        Admin.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaTestContainer.bootstrapServers()))) {
      Map<TopicPartition, OffsetAndMetadata> offsets =
          admin
              .listConsumerGroupOffsets("beacon-reconciliation")
              .partitionsToOffsetAndMetadata()
              .get(WAIT.toSeconds(), TimeUnit.SECONDS);
      OffsetAndMetadata committed =
          offsets.get(new TopicPartition(KafkaTestContainer.RECONCILIATION_TOPIC, partition));
      return committed == null ? -1L : committed.offset();
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("cannot list group offsets", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  protected double counter(String name, String... tags) {
    var counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0.0 : counter.count();
  }

  /**
   * Schema-validated DLQ records from stream-reconciliation.v1 whose original bytes are exactly
   * {@code original} (the broker is shared with the materializer tests, which park the same junk
   * bytes from climber-features.v1 on the DLQ).
   */
  protected List<JsonNode> dlqRecordsFor(byte[] original) {
    String base64 = Base64.getEncoder().encodeToString(original);
    return KafkaTestContainer.readToEnd(KafkaTestContainer.DLQ_TOPIC).stream()
        .filter(r -> originalValueBase64(r.value()).equals(base64))
        .map(r -> validDlq(r.value()))
        .filter(
            node ->
                KafkaTestContainer.RECONCILIATION_TOPIC.equals(
                    node.get("original_topic").asString()))
        .toList();
  }

  protected ConsumerRecord<String, byte[]> dlqRecordOnTopic(String dlqEventId) {
    return KafkaTestContainer.readToEnd(KafkaTestContainer.DLQ_TOPIC).stream()
        .filter(r -> new String(r.value(), StandardCharsets.UTF_8).contains(dlqEventId))
        .findFirst()
        .orElseThrow();
  }

  private static String originalValueBase64(byte[] value) {
    try {
      JsonNode node = MAPPER.readTree(value);
      JsonNode member = node == null ? null : node.get("original_value_base64");
      return member == null || !member.isString() ? "" : member.asString();
    } catch (RuntimeException e) {
      return "";
    }
  }

  private JsonNode validDlq(byte[] value) {
    ValidationResult result = validator.validate(EventSchemas.DLQ, value);
    assertThat(result).as(new String(value, StandardCharsets.UTF_8)).isInstanceOf(Valid.class);
    return ((Valid) result).payload();
  }
}
