package com.beaconclimb.stream;

import com.beaconclimb.stream.features.ClimberFeaturesFunction;
import java.time.Duration;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;

/**
 * The job graph between the sources and the sinks, so {@link StreamJob} (Kafka) and the MiniCluster
 * test (collection source, list sinks) run the identical operator chain.
 */
public final class StreamTopology {

  /** Bounded out-of-orderness: events arriving later than this are beyond the lateness window. */
  public static final Duration MAX_OUT_OF_ORDERNESS = Duration.ofHours(24);

  /** After this long without events a source split stops holding the watermark back. */
  public static final Duration IDLENESS = Duration.ofMinutes(1);

  /**
   * The three streams leaving the topology; each goes to its own sink ({@code invalid} is the
   * stream-dlq.v1 feed: value = DLQ record, key = original key, headers = DLQ headers).
   */
  public record Outputs(
      DataStream<KeyedBytes> features,
      DataStream<KeyedBytes> late,
      DataStream<KeyedBytes> invalid) {}

  private StreamTopology() {}

  /** Production watermarks: periodic, 24 h bound, idle splits ignored after one minute. */
  public static WatermarkStrategy<AttemptEvent> watermarks() {
    return WatermarkStrategy.<AttemptEvent>forBoundedOutOfOrderness(MAX_OUT_OF_ORDERNESS)
        .withIdleness(IDLENESS)
        .withTimestampAssigner(timestampAssigner());
  }

  /** Event time is {@code occurred_at}, never ingestion or arrival time (ADR-0004). */
  public static SerializableTimestampAssigner<AttemptEvent> timestampAssigner() {
    return (event, previous) -> event.occurredAtMillis();
  }

  /**
   * @param rawAttempts the source records of {@code attemptsTopic}
   * @param watermarks the watermark strategy on {@code occurred_at}
   * @param attemptsTopic the source topic name, recorded in every DLQ record
   */
  public static Outputs wire(
      DataStream<SourceRecord> rawAttempts,
      WatermarkStrategy<AttemptEvent> watermarks,
      String attemptsTopic) {
    SingleOutputStreamOperator<AttemptEvent> parsed =
        rawAttempts
            .process(new ParseAttempts(attemptsTopic))
            .name("parse-attempts")
            .uid("parse-attempts");
    SingleOutputStreamOperator<KeyedBytes> features =
        parsed
            .assignTimestampsAndWatermarks(watermarks)
            .name("occurred-at-watermarks")
            .uid("occurred-at-watermarks")
            .keyBy(AttemptEvent::userId)
            .process(new ClimberFeaturesFunction())
            .name("climber-features")
            .uid("climber-features");
    return new Outputs(
        features,
        features.getSideOutput(ClimberFeaturesFunction.LATE),
        parsed.getSideOutput(ParseAttempts.INVALID));
  }
}
