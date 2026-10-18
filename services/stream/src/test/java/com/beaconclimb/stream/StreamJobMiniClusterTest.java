package com.beaconclimb.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.common.eventtime.WatermarkGenerator;
import org.apache.flink.api.common.eventtime.WatermarkOutput;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The BC-0303 topology on an in-JVM Flink MiniCluster: the same operator chain as {@link
 * StreamJob}, with a bounded collection in place of the KafkaSource and {@link ListSink}s in place
 * of the KafkaSinks. Watermarks are emitted per element (punctuated, same 24 h bound as production)
 * so the late-event path is deterministic rather than timer-dependent.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class StreamJobMiniClusterTest {

  @RegisterExtension
  static final MiniClusterExtension MINI_CLUSTER =
      new MiniClusterExtension(
          new MiniClusterResourceConfiguration.Builder()
              .setNumberTaskManagers(1)
              .setNumberSlotsPerTaskManager(2)
              .build());

  static final String FEATURES = "features";
  static final String LATE = "late";
  static final String INVALID = "invalid";
  static final Instant ON_TIME = Instant.parse("2026-10-14T14:00:00Z");
  static final Instant THREE_DAYS_EARLIER = Instant.parse("2026-10-11T14:00:00Z");

  @Test
  @DisplayName(
      "3 valid attempts -> 3 schema-valid climber-features.v1 placeholders keyed by user_id; a 3-day-late event lands in the reconciliation side output; junk bytes in the invalid side output")
  void roundTrip() throws Exception {
    ObjectNode late = ExampleEvents.example("valid-attempt-recorded");
    late.put("event_id", "018f0000-0000-7000-8000-00000000000a");
    late.put("attempt_id", "018f0000-0000-7000-8000-00000000000b");
    late.put("occurred_at", THREE_DAYS_EARLIER.toString());
    List<byte[]> input =
        List.of(
            ExampleEvents.bytes(ExampleEvents.example("valid-attempt-recorded")),
            ExampleEvents.bytes(ExampleEvents.example("valid-attempt-corrected")),
            ExampleEvents.bytes(ExampleEvents.example("valid-attempt-retracted")),
            ExampleEvents.bytes(late),
            "{\"junk\":true}".getBytes(StandardCharsets.UTF_8));

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(1);
    DataStream<byte[]> raw =
        env.fromData(input, PrimitiveArrayTypeInfo.BYTE_PRIMITIVE_ARRAY_TYPE_INFO);
    StreamTopology.Outputs outputs = StreamTopology.wire(raw, punctuatedWatermarks());
    outputs.features().sinkTo(new ListSink<>(FEATURES));
    outputs.late().sinkTo(new ListSink<>(LATE));
    outputs.invalid().sinkTo(new ListSink<>(INVALID));
    env.execute("bc-0303-minicluster");

    List<KeyedBytes> features = ListSink.collected(FEATURES);
    assertThat(features).hasSize(3);
    ClimberFeaturesSchema schema = new ClimberFeaturesSchema();
    for (KeyedBytes record : features) {
      JsonNode json =
          ExampleEvents.MAPPER.readTree(new String(record.value, StandardCharsets.UTF_8));
      assertThat(schema.validate(json)).as(json.toString()).isEmpty();
      assertThat(record.key).isEqualTo(ExampleEvents.USER_ID);
      assertThat(json.get("user_id").asString()).isEqualTo(ExampleEvents.USER_ID);
      assertThat(json.get("producer").asString()).isEqualTo("stream");
      assertThat(json.get("feature_as_of").asString()).isEqualTo(ON_TIME.toString());
      // W = max(current watermark, occurred_at) and the watermark trails event time by 24 h.
      assertThat(json.get("source_watermark").asString()).isEqualTo(ON_TIME.toString());
      assertThat(json.get("provenance_checksum").asString())
          .isEqualTo(PlaceholderFeatureRecord.EMPTY_SET_CHECKSUM);
      assertThat(json.get("aggregates").get("attempts_90d").asInt()).isZero();
    }

    List<KeyedBytes> lateRecords = ListSink.collected(LATE);
    assertThat(lateRecords).hasSize(1);
    KeyedBytes lateRecord = lateRecords.get(0);
    assertThat(lateRecord.key).isEqualTo(ExampleEvents.USER_ID);
    assertThat(lateRecord.value).isEqualTo(ExampleEvents.bytes(late));
    assertThat(lateRecord.headers)
        .containsEntry(PlaceholderFeatures.HEADER_REASON, PlaceholderFeatures.REASON_LATE)
        .containsKey(PlaceholderFeatures.HEADER_WATERMARK);
    assertThat(Instant.parse(lateRecord.headers.get(PlaceholderFeatures.HEADER_WATERMARK)))
        .isAfter(THREE_DAYS_EARLIER)
        .isBefore(ON_TIME);

    List<byte[]> invalid = ListSink.collected(INVALID);
    assertThat(invalid).hasSize(1);
    assertThat(new String(invalid.get(0), StandardCharsets.UTF_8)).isEqualTo("{\"junk\":true}");
  }

  /**
   * Same timestamp and 24 h bound as {@link StreamTopology#watermarks()}, but the watermark is
   * emitted on every event instead of on the 200 ms periodic timer, so the fourth element is
   * guaranteed to see the watermark produced by the first three.
   */
  static WatermarkStrategy<AttemptEvent> punctuatedWatermarks() {
    long boundMillis = StreamTopology.MAX_OUT_OF_ORDERNESS.toMillis();
    return WatermarkStrategy.<AttemptEvent>forGenerator(
            context ->
                new WatermarkGenerator<>() {
                  private long maxTimestamp = Long.MIN_VALUE;

                  @Override
                  public void onEvent(AttemptEvent event, long timestamp, WatermarkOutput output) {
                    maxTimestamp = Math.max(maxTimestamp, timestamp);
                    output.emitWatermark(new Watermark(maxTimestamp - boundMillis - 1));
                  }

                  @Override
                  public void onPeriodicEmit(WatermarkOutput output) {}
                })
        .withTimestampAssigner(StreamTopology.timestampAssigner());
  }
}
