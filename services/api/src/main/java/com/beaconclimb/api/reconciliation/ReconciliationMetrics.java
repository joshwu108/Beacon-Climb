package com.beaconclimb.api.reconciliation;

import com.beaconclimb.events.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Reconciliation meters (BC-0309), scraped at {@code /prometheus}: {@code
 * beacon_reconciliation_received_total} (rows inserted), {@code
 * beacon_reconciliation_duplicates_total} (redelivered event_ids, no-op), {@code
 * beacon_reconciliation_invalid_total{error_code}} (payloads the shared validator rejected, sent to
 * the DLQ), {@code beacon_reconciliation_resolved_total{resolution}} (rows the repair job closed or
 * marked ERROR) and the gauge {@code beacon_reconciliation_open_rows}.
 *
 * <p>The gauge is one {@code count(*)} over the partial index of open rows, taken at most once per
 * {@link #OPEN_ROWS_TTL} when the registry is read, so a scrape never costs more than one cheap
 * query and an idle api costs nothing. Not gated on {@code beacon.stream.enabled}: open rows wait
 * for {@code make reconcile} whether or not this process consumes Kafka, and the {@code reconcile}
 * profile increments {@code resolved_total} without a broker.
 */
@Component
public class ReconciliationMetrics {

  public static final String RECEIVED = "beacon.reconciliation.received";
  public static final String DUPLICATES = "beacon.reconciliation.duplicates";
  public static final String INVALID = "beacon.reconciliation.invalid";
  public static final String RESOLVED = "beacon.reconciliation.resolved";
  public static final String OPEN_ROWS = "beacon.reconciliation.open_rows";
  public static final String ERROR_CODE_TAG = "error_code";
  public static final String RESOLUTION_TAG = "resolution";
  static final Duration OPEN_ROWS_TTL = Duration.ofSeconds(15);

  private record Sample(Instant at, double value) {}

  private final MeterRegistry registry;
  private final ReconciliationRepository repository;
  private final Clock clock;
  private final Counter received;
  private final Counter duplicates;
  private final AtomicReference<Sample> openRows = new AtomicReference<>();

  public ReconciliationMetrics(
      MeterRegistry registry, ReconciliationRepository repository, Clock clock) {
    this.registry = registry;
    this.repository = repository;
    this.clock = clock;
    this.received =
        Counter.builder(RECEIVED)
            .description("stream-reconciliation.v1 records inserted into reconciliation_events")
            .register(registry);
    this.duplicates =
        Counter.builder(DUPLICATES)
            .description("stream-reconciliation.v1 records whose event_id was already stored")
            .register(registry);
    Gauge.builder(OPEN_ROWS, this, ReconciliationMetrics::openRows)
        .description("reconciliation_events rows with resolved_at IS NULL (refreshed every 15 s)")
        .register(registry);
  }

  public void received() {
    received.increment();
  }

  public void duplicate() {
    duplicates.increment();
  }

  public void invalid(ErrorCode code) {
    Counter.builder(INVALID)
        .tag(ERROR_CODE_TAG, code.name())
        .description("stream-reconciliation.v1 payloads the shared validator rejected (DLQ)")
        .register(registry)
        .increment();
  }

  public void resolved(Resolution resolution, int rows) {
    Counter.builder(RESOLVED)
        .tag(RESOLUTION_TAG, resolution.name())
        .description("reconciliation_events rows the repair job closed, by resolution")
        .register(registry)
        .increment(rows);
  }

  /** Forgets the cached sample so the next read of the gauge queries again (tests). */
  public void refreshOpenRows() {
    openRows.set(null);
  }

  double openRows() {
    Instant now = clock.instant();
    Sample cached = openRows.get();
    if (cached != null && cached.at().plus(OPEN_ROWS_TTL).isAfter(now)) {
      return cached.value();
    }
    double value;
    try {
      value = repository.countOpen();
    } catch (RuntimeException e) {
      return cached == null ? Double.NaN : cached.value();
    }
    openRows.set(new Sample(now, value));
    return value;
  }
}
