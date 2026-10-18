package com.beaconclimb.api.outbox;

import com.beaconclimb.api.outbox.OutboxMetrics.FailureReason;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional-outbox publisher (BC-0301, ADR-0002 "Transactional outbox and events"). Every poll
 * runs one transaction: lock up to {@code batch-size} unpublished rows oldest first ({@code FOR
 * UPDATE SKIP LOCKED}), send each to {@code attempts.v1} keyed by {@code user_id} with the stored
 * payload bytes and the headers {@code event_id}, {@code event_type} and {@code schema_version},
 * wait for every acknowledgement, then set {@code published_at} and commit.
 *
 * <p>Any failure rolls the transaction back, so the rows stay unpublished and the next poll retries
 * them; a crash between the acknowledgements and the commit therefore publishes a row twice.
 * Delivery is at-least-once and consumers dedupe on {@code (attempt_id, attempt_revision)}
 * (ADR-0002). The request path never calls this class: an attempt write only inserts its row.
 *
 * <p>Failures are counted by reason and logged at WARN at most once per {@link #WARN_INTERVAL}
 * (DEBUG in between) so a long broker outage does not flood the log. After every poll the three
 * backlog gauges are refreshed from one aggregate over the unpublished partial index.
 */
public class OutboxPublisher {

  static final Duration WARN_INTERVAL = Duration.ofSeconds(30);
  static final String HEADER_EVENT_ID = "event_id";
  static final String HEADER_EVENT_TYPE = "event_type";
  static final String HEADER_SCHEMA_VERSION = "schema_version";
  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxRepository repository;
  private final KafkaTemplate<String, byte[]> kafka;
  private final StreamProperties properties;
  private final OutboxMetrics metrics;
  private final TransactionTemplate tx;

  private volatile boolean warnedBefore;
  private volatile long lastWarnNanos;
  private volatile boolean failing;

  public OutboxPublisher(
      OutboxRepository repository,
      KafkaTemplate<String, byte[]> kafka,
      StreamProperties properties,
      OutboxMetrics metrics,
      TransactionTemplate tx) {
    this.repository = repository;
    this.kafka = kafka;
    this.properties = properties;
    this.metrics = metrics;
    this.tx = tx;
  }

  /** The scheduled poll: one batch, failures swallowed after counting and logging. */
  @Scheduled(fixedDelayString = "${beacon.stream.publisher.poll-interval}")
  public void poll() {
    try {
      int published = publishBatch();
      if (published > 0 && failing) {
        failing = false;
        log.info("outbox outcome=RECOVERED rows={}", published);
      }
    } catch (PublishFailure e) {
      failed(e.reason, e.rows, e.getCause() != null ? e.getCause() : e);
    } catch (DataAccessException e) {
      failed(FailureReason.DATABASE, -1, e);
    } catch (RuntimeException e) {
      failed(FailureReason.OTHER, -1, e);
    } finally {
      refreshGauges();
    }
  }

  /**
   * One batch in one transaction. Returns the number of rows published (0 when the outbox is
   * empty); throws {@link PublishFailure} or a {@link DataAccessException} after rolling back.
   */
  public int publishBatch() {
    Integer published = tx.execute(status -> sendLockedBatch());
    int rows = published == null ? 0 : published;
    if (rows > 0) {
      metrics.published(rows);
      log.debug("outbox outcome=PUBLISHED rows={}", rows);
    }
    return rows;
  }

  /** Refreshes the backlog gauges; a database error here is logged, never thrown. */
  public void refreshGauges() {
    try {
      metrics.backlog(repository.backlog(properties.publisher().staleAfter()));
    } catch (DataAccessException e) {
      log.debug("outbox outcome=GAUGE_REFRESH_FAILED error={}", e.toString());
    }
  }

  private int sendLockedBatch() {
    List<OutboxRow> rows = repository.lockUnpublished(properties.publisher().batchSize());
    if (rows.isEmpty()) {
      return 0;
    }
    List<CompletableFuture<SendResult<String, byte[]>>> sends;
    try {
      sends = rows.stream().map(this::send).toList();
    } catch (KafkaException e) {
      throw new PublishFailure(FailureReason.KAFKA, rows.size(), e);
    }
    awaitAll(sends, rows.size());
    repository.markPublished(rows.stream().map(OutboxRow::eventId).toList());
    return rows.size();
  }

  private CompletableFuture<SendResult<String, byte[]>> send(OutboxRow row) {
    ProducerRecord<String, byte[]> record =
        new ProducerRecord<>(
            properties.topics().attempts(),
            row.userId(),
            row.payloadJson().getBytes(StandardCharsets.UTF_8));
    record.headers().add(HEADER_EVENT_ID, utf8(row.eventId()));
    record.headers().add(HEADER_EVENT_TYPE, utf8(row.eventType()));
    record.headers().add(HEADER_SCHEMA_VERSION, utf8(row.schemaVersion()));
    return kafka.send(record);
  }

  /** Waits for every send against one deadline: delivery timeout plus one request timeout. */
  private void awaitAll(List<CompletableFuture<SendResult<String, byte[]>>> sends, int rows) {
    StreamProperties.Publisher publisher = properties.publisher();
    long deadline =
        System.nanoTime() + publisher.deliveryTimeout().plus(publisher.requestTimeout()).toNanos();
    for (CompletableFuture<SendResult<String, byte[]>> send : sends) {
      long remaining = deadline - System.nanoTime();
      try {
        send.get(Math.max(remaining, 1), TimeUnit.NANOSECONDS);
      } catch (TimeoutException e) {
        throw new PublishFailure(FailureReason.TIMEOUT, rows, e);
      } catch (ExecutionException e) {
        throw new PublishFailure(FailureReason.KAFKA, rows, e.getCause());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new PublishFailure(FailureReason.OTHER, rows, e);
      }
    }
  }

  private void failed(FailureReason reason, int rows, Throwable error) {
    failing = true;
    metrics.failure(reason);
    long now = System.nanoTime();
    boolean warn = !warnedBefore || now - lastWarnNanos >= WARN_INTERVAL.toNanos();
    if (warn) {
      warnedBefore = true;
      lastWarnNanos = now;
      log.warn(
          "outbox outcome=PUBLISH_FAILED reason={} rows={} error={}",
          reason.tag(),
          rows,
          error.toString());
    } else {
      log.debug(
          "outbox outcome=PUBLISH_FAILED reason={} rows={} error={}",
          reason.tag(),
          rows,
          error.toString());
    }
  }

  private static byte[] utf8(Object value) {
    return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
  }

  /** Thrown inside the transaction so it rolls back; carries the counted reason. */
  static final class PublishFailure extends RuntimeException {
    final FailureReason reason;
    final int rows;

    PublishFailure(FailureReason reason, int rows, Throwable cause) {
      super("outbox batch of " + rows + " rows failed: " + reason.tag(), cause);
      this.reason = reason;
      this.rows = rows;
    }
  }
}
