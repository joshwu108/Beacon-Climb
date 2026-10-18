package com.beaconclimb.api.materializer;

import com.beaconclimb.api.materializer.MaterializerService.Outcome;
import com.beaconclimb.events.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * Materializer meters (BC-0305), scraped at {@code /prometheus} like the outbox ones: {@code
 * beacon_materializer_applied_total}, {@code beacon_materializer_skipped_total{reason=duplicate|
 * stale}}, {@code beacon_materializer_conflict_total{reason=checksum|partition}}, {@code
 * beacon_materializer_invalid_total{error_code}} and {@code beacon_materializer_errors_total}.
 * Consumer lag is the Kafka client's own {@code kafka_consumer_fetch_manager_records_lag} family,
 * registered by the {@code MicrometerConsumerListener} in {@link MaterializerConfig} with the tag
 * {@code spring_id=beacon-api-materializer}; no separate gauge is polled.
 */
public class MaterializerMetrics {

  public static final String APPLIED = "beacon.materializer.applied";
  public static final String SKIPPED = "beacon.materializer.skipped";
  public static final String CONFLICT = "beacon.materializer.conflict";
  public static final String INVALID = "beacon.materializer.invalid";
  public static final String ERRORS = "beacon.materializer.errors";
  public static final String REASON_TAG = "reason";
  public static final String ERROR_CODE_TAG = "error_code";

  private final MeterRegistry registry;
  private final Counter applied;
  private final Counter skippedDuplicate;
  private final Counter skippedStale;
  private final Counter conflictChecksum;
  private final Counter conflictPartition;
  private final Counter errors;

  public MaterializerMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.applied =
        Counter.builder(APPLIED)
            .description("climber-features.v1 records written to the STREAM snapshot row")
            .register(registry);
    this.skippedDuplicate = skipped(registry, Outcome.DUPLICATE);
    this.skippedStale = skipped(registry, Outcome.STALE);
    this.conflictChecksum = conflict(registry, "checksum");
    this.conflictPartition = conflict(registry, "partition");
    this.errors =
        Counter.builder(ERRORS)
            .description("records whose transaction or DLQ write failed and will be redelivered")
            .register(registry);
  }

  public void outcome(Outcome outcome) {
    switch (outcome) {
      case APPLIED -> applied.increment();
      case DUPLICATE -> skippedDuplicate.increment();
      case STALE -> skippedStale.increment();
      case CHECKSUM_CONFLICT -> conflictChecksum.increment();
      case PARTITION_MISMATCH -> conflictPartition.increment();
    }
  }

  public void invalid(ErrorCode code) {
    Counter.builder(INVALID)
        .tag(ERROR_CODE_TAG, code.name())
        .description("climber-features.v1 records the shared validator rejected (sent to the DLQ)")
        .register(registry)
        .increment();
  }

  public void error() {
    errors.increment();
  }

  private static Counter skipped(MeterRegistry registry, Outcome outcome) {
    return Counter.builder(SKIPPED)
        .tag(REASON_TAG, outcome.name().toLowerCase(Locale.ROOT))
        .description("records that left the STREAM row unchanged, by reason")
        .register(registry);
  }

  private static Counter conflict(MeterRegistry registry, String reason) {
    return Counter.builder(CONFLICT)
        .tag(REASON_TAG, reason)
        .description("records sent to the DLQ with CHECKSUM_CONFLICT, by reason")
        .register(registry);
  }
}
