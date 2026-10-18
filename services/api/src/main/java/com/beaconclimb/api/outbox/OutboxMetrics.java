package com.beaconclimb.api.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox meters (BC-0301). Micrometer names are dotted; the Prometheus scrape at {@code
 * /prometheus} renders them as {@code beacon_outbox_backlog_rows}, {@code
 * beacon_outbox_backlog_age_seconds}, {@code beacon_outbox_stale_rows}, {@code
 * beacon_outbox_published_total} and {@code beacon_outbox_publish_failures_total{reason}}. The
 * three gauges are refreshed from one SQL aggregate after every poll ({@link
 * OutboxRepository#backlog}); the counters are incremented by the publisher.
 */
public class OutboxMetrics {

  public static final String BACKLOG_ROWS = "beacon.outbox.backlog.rows";
  public static final String BACKLOG_AGE_SECONDS = "beacon.outbox.backlog.age.seconds";
  public static final String STALE_ROWS = "beacon.outbox.stale.rows";
  public static final String PUBLISHED = "beacon.outbox.published";
  public static final String PUBLISH_FAILURES = "beacon.outbox.publish.failures";
  public static final String REASON_TAG = "reason";

  /** Why a batch was rolled back; the bounded {@code reason} tag of the failure counter. */
  public enum FailureReason {
    /** The producer rejected or failed a send (broker unreachable, delivery timeout). */
    KAFKA,
    /** The batch wait expired before every send had been acknowledged. */
    TIMEOUT,
    /** Postgres refused the lock, read or update. */
    DATABASE,
    /** Anything else; the log line carries the exception. */
    OTHER;

    String tag() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  private final AtomicLong backlogRows = new AtomicLong();
  private final AtomicLong backlogAgeMillis = new AtomicLong();
  private final AtomicLong staleRows = new AtomicLong();
  private final Counter published;
  private final Map<FailureReason, Counter> failures = new EnumMap<>(FailureReason.class);

  public OutboxMetrics(MeterRegistry registry) {
    Gauge.builder(BACKLOG_ROWS, backlogRows, AtomicLong::get)
        .description("attempt_event_outbox rows with published_at IS NULL")
        .register(registry);
    Gauge.builder(BACKLOG_AGE_SECONDS, backlogAgeMillis, v -> v.get() / 1000.0)
        .description("now minus the oldest unpublished created_at; 0 when the outbox is empty")
        .register(registry);
    Gauge.builder(STALE_ROWS, staleRows, AtomicLong::get)
        .description("unpublished rows older than beacon.stream.publisher.stale-after")
        .register(registry);
    this.published =
        Counter.builder(PUBLISHED)
            .description("outbox rows acknowledged by Kafka and marked published")
            .register(registry);
    for (FailureReason reason : FailureReason.values()) {
      failures.put(
          reason,
          Counter.builder(PUBLISH_FAILURES)
              .tag(REASON_TAG, reason.tag())
              .description("outbox batches rolled back, by reason")
              .register(registry));
    }
  }

  public void backlog(OutboxRepository.Backlog backlog) {
    backlogRows.set(backlog.rows());
    backlogAgeMillis.set(Math.round(backlog.ageSeconds() * 1000.0));
    staleRows.set(backlog.staleRows());
  }

  public void published(int rows) {
    published.increment(rows);
  }

  public void failure(FailureReason reason) {
    failures.get(reason).increment();
  }
}
