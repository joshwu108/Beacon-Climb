package com.beaconclimb.api.dlq;

import com.beaconclimb.api.outbox.StreamProperties;
import com.beaconclimb.events.DlqRecord;
import com.beaconclimb.events.ErrorCode;
import com.beaconclimb.events.ValidationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Writes stream-dlq.v1 records for the api's consumers (BC-0302; the materializer of BC-0305 is the
 * first caller). Synchronous on purpose: the caller acknowledges its source record only after this
 * returns, so a failed DLQ write surfaces as a redelivery rather than a lost record (at-least-once,
 * ADR-0004; a crash between the DLQ acknowledgement and the source ack produces a duplicate DLQ
 * record, which the redrive dedupes by {@code event_id}).
 *
 * <p>Meters: {@code beacon_dlq_published_total{consumer="api",error_code}} and {@code
 * beacon_dlq_publish_failures_total}. The log line carries ids and coordinates, never the bytes.
 */
public class DlqPublisher {

  public static final String CONSUMER = "api";
  public static final String PUBLISHED = "beacon.dlq.published";
  public static final String PUBLISH_FAILURES = "beacon.dlq.publish.failures";
  public static final String ERROR_CODE_TAG = "error_code";
  public static final String CONSUMER_TAG = "consumer";
  private static final Logger log = LoggerFactory.getLogger(DlqPublisher.class);

  private final KafkaTemplate<String, byte[]> kafka;
  private final StreamProperties properties;
  private final MeterRegistry registry;
  private final Counter failures;

  public DlqPublisher(
      KafkaTemplate<String, byte[]> kafka, StreamProperties properties, MeterRegistry registry) {
    this.kafka = kafka;
    this.properties = properties;
    this.registry = registry;
    this.failures =
        Counter.builder(PUBLISH_FAILURES)
            .tag(CONSUMER_TAG, CONSUMER)
            .description("stream-dlq.v1 writes that failed or timed out")
            .register(registry);
  }

  /** The DLQ record for a consumer record the shared validator rejected. */
  public DlqRecord publish(ConsumerRecord<String, byte[]> rejected, ValidationResult.Invalid why) {
    return publish(rejected, why.errorCode(), why.message());
  }

  /**
   * Builds and publishes the DLQ record of {@code rejected}: original bytes, key, partition, offset
   * and headers (the attempt number comes from a {@code dlq.attempt} header when the record was
   * itself a redrive).
   */
  public DlqRecord publish(
      ConsumerRecord<String, byte[]> rejected, ErrorCode errorCode, String errorMessage) {
    DlqRecord record =
        DlqRecord.builder()
            .originalTopic(rejected.topic())
            .originalKey(rejected.key())
            .originalValue(rejected.value() == null ? new byte[0] : rejected.value())
            .originalPartition(rejected.partition())
            .originalOffset(rejected.offset())
            .originalHeaders(headersOf(rejected))
            .error(errorCode, errorMessage)
            .consumer(CONSUMER)
            .failedAt(Instant.now())
            .build();
    return publish(record);
  }

  /** Publishes an already built record (value, key and headers from the record) and waits. */
  public DlqRecord publish(DlqRecord record) {
    ProducerRecord<String, byte[]> producerRecord =
        new ProducerRecord<>(properties.topics().dlq(), record.originalKey(), record.json());
    record
        .headers()
        .forEach(
            (name, value) ->
                producerRecord.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
    StreamProperties.Publisher publisher = properties.publisher();
    Duration wait = publisher.deliveryTimeout().plus(publisher.requestTimeout());
    try {
      kafka.send(producerRecord).get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      failures.increment();
      throw new DlqPublishException(record, e instanceof ExecutionException ? e.getCause() : e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failures.increment();
      throw new DlqPublishException(record, e);
    }
    Counter.builder(PUBLISHED)
        .tag(CONSUMER_TAG, CONSUMER)
        .tag(ERROR_CODE_TAG, record.errorCode())
        .description("stream-dlq.v1 records written by the api")
        .register(registry)
        .increment();
    log.warn(
        "dlq outcome=PUBLISHED event_id={} original_topic={} original_partition={} original_offset={} error_code={} attempt={}",
        record.eventId(),
        record.originalTopic(),
        record.originalPartition(),
        record.originalOffset(),
        record.errorCode(),
        record.attempt());
    return record;
  }

  static Map<String, String> headersOf(ConsumerRecord<?, ?> record) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (Header header : record.headers()) {
      if (header.value() != null) {
        headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
      }
    }
    return headers;
  }

  /** The DLQ write did not complete; the caller must not acknowledge its source record. */
  public static final class DlqPublishException extends RuntimeException {
    private final transient DlqRecord record;

    DlqPublishException(DlqRecord record, Throwable cause) {
      super("stream-dlq.v1 write failed for " + record, cause);
      this.record = record;
    }

    public DlqRecord record() {
      return record;
    }
  }
}
