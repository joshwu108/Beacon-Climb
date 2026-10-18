package com.beaconclimb.stream;

import java.time.Instant;
import java.util.Map;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

/**
 * BC-0303 PLACEHOLDER operator, keyed by user_id. For every on-time event it emits one
 * climber-features.v1 record built by {@link PlaceholderFeatureRecord} (empty-set aggregates);
 * BC-0304 replaces the body with the MapState-backed computation. What is already final here and
 * must survive that replacement:
 *
 * <ul>
 *   <li>the cutoff rule {@code W = max(currentWatermark, occurred_at of the element just applied)}
 *       (design note "parity subtlety"), reported as {@code source_watermark};
 *   <li>late routing: an element whose timestamp is below the current watermark (which already
 *       trails event time by the 24 h bound) is beyond the lateness window and goes to {@link
 *       #LATE} with its ORIGINAL bytes, reason LATE_BEYOND_WINDOW and the watermark, for
 *       stream-reconciliation.v1 (ADR-0004).
 * </ul>
 */
public final class PlaceholderFeatures
    extends KeyedProcessFunction<String, AttemptEvent, KeyedBytes> {

  private static final long serialVersionUID = 1L;

  public static final OutputTag<KeyedBytes> LATE =
      new OutputTag<>("late-attempts", TypeInformation.of(KeyedBytes.class));
  public static final String HEADER_REASON = "reason";
  public static final String HEADER_WATERMARK = "watermark";
  public static final String REASON_LATE = "LATE_BEYOND_WINDOW";

  private transient Counter emitted;
  private transient Counter late;

  @Override
  public void open(org.apache.flink.api.common.functions.OpenContext openContext) {
    emitted = getRuntimeContext().getMetricGroup().counter("features_emitted");
    late = getRuntimeContext().getMetricGroup().counter("attempts_late");
  }

  @Override
  public void processElement(AttemptEvent event, Context ctx, Collector<KeyedBytes> out) {
    long watermark = ctx.timerService().currentWatermark();
    long occurredAt = event.occurredAtMillis();
    if (watermark != Long.MIN_VALUE && occurredAt < watermark) {
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
                  PlaceholderFeatureRecord.iso(watermark))));
      return;
    }
    long cutoffW = Math.max(watermark, occurredAt);
    byte[] record =
        PlaceholderFeatureRecord.build(event.userId(), occurredAt, cutoffW, Instant.now());
    emitted.inc();
    out.collect(KeyedBytes.of(event.userId(), record));
  }
}
