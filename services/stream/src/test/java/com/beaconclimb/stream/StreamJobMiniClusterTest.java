package com.beaconclimb.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.events.DlqHeaders;
import com.beaconclimb.events.EventSchemas;
import com.beaconclimb.events.EventValidator;
import com.beaconclimb.events.ValidationResult;
import com.beaconclimb.events.ValidationResult.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.common.eventtime.WatermarkGenerator;
import org.apache.flink.api.common.eventtime.WatermarkOutput;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.TypeInformation;
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
 * The topology on an in-JVM Flink MiniCluster: the same operator chain as {@link StreamJob}, with a
 * bounded collection in place of the KafkaSource and {@link ListSink}s in place of the KafkaSinks.
 * Watermarks are emitted per element (punctuated, same 24 h bound as production) so the late-event
 * path is deterministic rather than timer-dependent. Invalid records (BC-0302) must leave as
 * schema-valid stream-dlq.v1 records carrying the original bytes and the DLQ headers.
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
  static final String TOPIC = "attempts.v1";
  static final Instant ON_TIME = Instant.parse("2026-10-14T14:00:00Z");
  static final Instant THREE_DAYS_EARLIER = Instant.parse("2026-10-11T14:00:00Z");
  static final String REDRIVEN_FROM = "018f0000-0000-7000-8000-0000000000dd";

  static final EventValidator VALIDATOR = new EventValidator();

  @Test
  @DisplayName(
      "3 valid attempts -> 3 schema-valid climber-features.v1 placeholders keyed by user_id; a 3-day-late event lands in the reconciliation side output; junk, notes, an unknown attribute and a redriven failure leave as stream-dlq.v1 records")
  void roundTrip() throws Exception {
    ObjectNode late = ExampleEvents.example("valid-attempt-recorded");
    late.put("event_id", "018f0000-0000-7000-8000-00000000000a");
    late.put("attempt_id", "018f0000-0000-7000-8000-00000000000b");
    late.put("occurred_at", THREE_DAYS_EARLIER.toString());
    byte[] junk = "{\"junk\":true".getBytes(StandardCharsets.UTF_8);
    byte[] notes = ExampleEvents.bytes(ExampleEvents.example("invalid-attempt-notes"));
    byte[] renamed =
        ExampleEvents.bytes(ExampleEvents.example("invalid-attempt-renamed-notes-comment"));
    List<SourceRecord> input =
        List.of(
            record(0L, ExampleEvents.bytes(ExampleEvents.example("valid-attempt-recorded"))),
            record(1L, ExampleEvents.bytes(ExampleEvents.example("valid-attempt-corrected"))),
            record(2L, ExampleEvents.bytes(ExampleEvents.example("valid-attempt-retracted"))),
            record(3L, ExampleEvents.bytes(late)),
            record(4L, junk),
            record(5L, notes),
            record(6L, renamed),
            // A record the redrive republished (attempt 2) that fails again.
            new SourceRecord(
                ExampleEvents.USER_ID,
                junk,
                2,
                7L,
                Map.of(
                    "event_id",
                    "x",
                    DlqHeaders.ATTEMPT,
                    "2",
                    DlqHeaders.REDRIVEN_FROM,
                    REDRIVEN_FROM,
                    DlqHeaders.ERROR_CODE,
                    "NOT_JSON",
                    DlqHeaders.CONSUMER,
                    "stream")));

    StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
    env.setParallelism(1);
    DataStream<SourceRecord> raw = env.fromData(input, TypeInformation.of(SourceRecord.class));
    StreamTopology.Outputs outputs = StreamTopology.wire(raw, punctuatedWatermarks(), TOPIC);
    outputs.features().sinkTo(new ListSink<>(FEATURES));
    outputs.late().sinkTo(new ListSink<>(LATE));
    outputs.invalid().sinkTo(new ListSink<>(INVALID));
    env.execute("bc-0302-minicluster");

    List<KeyedBytes> features = ListSink.collected(FEATURES);
    assertThat(features).hasSize(3);
    for (KeyedBytes record : features) {
      JsonNode json = validJson(EventSchemas.FEATURES, record.value);
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

    List<KeyedBytes> invalid = ListSink.collected(INVALID);
    assertThat(invalid).hasSize(4);
    Map<String, KeyedBytes> byOffset =
        invalid.stream()
            .collect(
                Collectors.toMap(
                    r -> r.headers.get(DlqHeaders.ORIGINAL_OFFSET), Function.identity()));
    assertThat(byOffset).containsOnlyKeys("4", "5", "6", "7");
    assertDlq(byOffset.get("4"), junk, "NOT_JSON", 1);
    assertDlq(byOffset.get("5"), notes, "NOTES_PRESENT", 1);
    assertDlq(byOffset.get("6"), renamed, "UNKNOWN_ATTRIBUTE", 1);
    KeyedBytes secondFailure = byOffset.get("7");
    assertDlq(secondFailure, junk, "NOT_JSON", 2);
    assertThat(secondFailure.headers)
        .containsEntry("event_id", "x")
        .containsEntry(DlqHeaders.ORIGINAL_PARTITION, "2")
        .doesNotContainKey(DlqHeaders.REDRIVEN_FROM);
    JsonNode notesDlq = validJson(EventSchemas.DLQ, byOffset.get("5").value);
    assertThat(notesDlq.get("error_message").asString())
        .contains("/attributes/notes")
        .doesNotContain("private note");
  }

  private static void assertDlq(KeyedBytes dlq, byte[] original, String code, int attempt) {
    JsonNode json = validJson(EventSchemas.DLQ, dlq.value);
    assertThat(dlq.key).isEqualTo(ExampleEvents.USER_ID);
    assertThat(json.get("original_topic").asString()).isEqualTo(TOPIC);
    assertThat(json.get("original_key").asString()).isEqualTo(ExampleEvents.USER_ID);
    assertThat(Base64.getDecoder().decode(json.get("original_value_base64").asString()))
        .isEqualTo(original);
    assertThat(json.get("error_code").asString()).isEqualTo(code);
    assertThat(json.get("attempt").asInt()).isEqualTo(attempt);
    assertThat(dlq.headers)
        .containsEntry(DlqHeaders.ORIGINAL_TOPIC, TOPIC)
        .containsEntry(DlqHeaders.ATTEMPT, Integer.toString(attempt))
        .containsEntry(DlqHeaders.ERROR_CODE, code)
        .containsEntry(DlqHeaders.CONSUMER, ParseAttempts.CONSUMER)
        .containsKey(DlqHeaders.ORIGINAL_PARTITION)
        .containsKey(DlqHeaders.ORIGINAL_OFFSET);
  }

  private static JsonNode validJson(String topic, byte[] bytes) {
    ValidationResult result = VALIDATOR.validate(topic, bytes);
    assertThat(result).as(new String(bytes, StandardCharsets.UTF_8)).isInstanceOf(Valid.class);
    return ((Valid) result).payload();
  }

  private static SourceRecord record(long offset, byte[] value) {
    return new SourceRecord(
        ExampleEvents.USER_ID,
        value,
        0,
        offset,
        Map.of("event_id", "018f0000-0000-7000-8000-0000000000" + (10 + offset)));
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
