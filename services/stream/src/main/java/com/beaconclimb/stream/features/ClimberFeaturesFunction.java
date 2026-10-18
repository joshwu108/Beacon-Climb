package com.beaconclimb.stream.features;

import com.beaconclimb.features.AttemptFeatureRow;
import com.beaconclimb.features.FeatureComputer;
import com.beaconclimb.stream.AttemptEvent;
import com.beaconclimb.stream.KeyedBytes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

/**
 * BC-0304: revision-aware per-user event-time aggregation (ADR-0004 processing rules,
 * contracts/features/climber-features.v1.md section 10). Keyed by {@code user_id}; {@code
 * MapState<attempt_id, AttemptState>} holds the highest revision seen per attempt, with a tombstone
 * for a retraction.
 *
 * <ul>
 *   <li>Late: {@code occurred_at < currentWatermark - 24 h} (the allowed lateness) is not applied
 *       and leaves through {@link #LATE} with the ORIGINAL bytes and the headers {@code
 *       reason=LATE_BEYOND_WINDOW}, {@code watermark=<W>} (stream-reconciliation.v1).
 *   <li>Revision: a revision higher than the stored one replaces the entry (ATTEMPT_RECORDED /
 *       ATTEMPT_CORRECTED) or tombstones it (ATTEMPT_RETRACTED); a revision lower than or equal to
 *       the stored one, including a duplicate delivery, changes nothing and emits nothing.
 *   <li>Cutoff: every applied change recomputes with the shared {@link FeatureComputer} over the
 *       non-tombstoned rows with {@code occurred_at <= W}, where {@code W = max(currentWatermark,
 *       occurred_at of this event)}; before any watermark exists (job start, and the first event
 *       after a restore, since watermarks are not checkpointed) {@code W = occurred_at}. Rows ahead
 *       of W stay in state and enter a later recompute. {@code source_watermark = W}, {@code
 *       feature_as_of} = max occurred_at of the included rows, or W when nothing is included.
 * </ul>
 */
public final class ClimberFeaturesFunction
    extends KeyedProcessFunction<String, AttemptEvent, KeyedBytes> {

  private static final long serialVersionUID = 1L;

  /** ADR-0004: events older than this behind the watermark enter reconciliation instead. */
  public static final Duration ALLOWED_LATENESS = Duration.ofHours(24);

  public static final OutputTag<KeyedBytes> LATE =
      new OutputTag<>("late-attempts", TypeInformation.of(KeyedBytes.class));
  public static final String HEADER_REASON = "reason";
  public static final String HEADER_WATERMARK = "watermark";
  public static final String REASON_LATE = "LATE_BEYOND_WINDOW";

  /**
   * Explicit state type: {@code Types.POJO} throws at job construction if {@link AttemptState} ever
   * stops being a Flink POJO (which would silently switch the state to Kryo, losing schema
   * evolution). The descriptor name is part of the checkpoint; do not rename it.
   */
  public static final TypeInformation<AttemptState> ATTEMPT_STATE = Types.POJO(AttemptState.class);

  public static final String STATE_NAME = "attempts-by-id";

  private transient MapState<String, AttemptState> attempts;
  private transient Counter emitted;
  private transient Counter late;
  private transient Counter stale;
  private transient Counter unkeyed;

  @Override
  public void open(OpenContext openContext) {
    attempts =
        getRuntimeContext()
            .getMapState(new MapStateDescriptor<>(STATE_NAME, Types.STRING, ATTEMPT_STATE));
    emitted = getRuntimeContext().getMetricGroup().counter("features_emitted");
    late = getRuntimeContext().getMetricGroup().counter("attempts_late");
    stale = getRuntimeContext().getMetricGroup().counter("attempts_stale_revision");
    unkeyed = getRuntimeContext().getMetricGroup().counter("attempts_without_user_id");
  }

  @Override
  public void processElement(AttemptEvent event, Context ctx, Collector<KeyedBytes> out)
      throws Exception {
    if (event.userId().isEmpty()) {
      unkeyed.inc();
      return;
    }
    long watermark = ctx.timerService().currentWatermark();
    long occurredAt = event.occurredAtMillis();
    if (isLate(occurredAt, watermark)) {
      late.inc();
      ctx.output(
          LATE,
          new KeyedBytes(
              event.userId(),
              event.raw(),
              Map.of(
                  HEADER_REASON,
                  REASON_LATE,
                  HEADER_WATERMARK,
                  ClimberFeatureRecord.iso(cutoff(watermark, occurredAt)))));
      return;
    }
    String attemptId = canonicalAttemptId(event.attemptId());
    AttemptState stored = attempts.get(attemptId);
    if (stored != null && event.attemptRevision() <= stored.revision) {
      stale.inc();
      return;
    }
    attempts.put(attemptId, AttemptState.of(event));
    long cutoffW = cutoff(watermark, occurredAt);
    FeatureComputer.Result result =
        FeatureComputer.compute(rowsThrough(cutoffW), Instant.ofEpochMilli(cutoffW));
    long featureAsOf = result.featureAsOf() == null ? cutoffW : result.featureAsOf().toEpochMilli();
    byte[] record =
        ClimberFeatureRecord.build(
            event.userId(),
            featureAsOf,
            cutoffW,
            result.checksum(),
            result.aggregates().toJson(),
            Instant.ofEpochMilli(ctx.timerService().currentProcessingTime()));
    emitted.inc();
    out.collect(KeyedBytes.of(event.userId(), record));
  }

  /** Beyond the allowed lateness; never true before the first watermark. */
  static boolean isLate(long occurredAt, long watermark) {
    return watermark != Long.MIN_VALUE && occurredAt < watermark - ALLOWED_LATENESS.toMillis();
  }

  /** W = max(current watermark, occurred_at); with no watermark yet, occurred_at. */
  static long cutoff(long watermark, long occurredAt) {
    return Math.max(watermark, occurredAt);
  }

  /**
   * Lowercase 36-character text, so the state key and the checksum agree whatever the producer
   * sent.
   */
  static String canonicalAttemptId(String attemptId) {
    return UUID.fromString(attemptId).toString();
  }

  private List<AttemptFeatureRow> rowsThrough(long cutoffW) throws Exception {
    List<AttemptFeatureRow> rows = new ArrayList<>();
    for (Map.Entry<String, AttemptState> entry : attempts.entries()) {
      AttemptState state = entry.getValue();
      if (!state.tombstone && state.occurredAtMillis <= cutoffW) {
        rows.add(state.toRow(UUID.fromString(entry.getKey())));
      }
    }
    return rows;
  }
}
