package com.beaconclimb.stream.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.features.AttemptFeatureRow;
import com.beaconclimb.features.FeatureComputer;
import com.beaconclimb.features.Jcs;
import com.beaconclimb.stream.KeyedBytes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClimberFeatureRecordTest {

  static final Instant W = Instant.parse("2026-10-14T14:00:00Z");

  @Test
  @DisplayName("the aggregates member is the canonical text byte for byte and the record validates")
  void embedsCanonicalAggregates() {
    FeatureComputer.Result result =
        FeatureComputer.compute(
            List.of(
                new AttemptFeatureRow(TestEvents.id(1), 1, "r:40", W.minusMillis(250), true, 20.5),
                new AttemptFeatureRow(TestEvents.id(2), 2, "s:40", W.minusSeconds(5), false, null)),
            W);
    String canonical = result.aggregates().toJson();

    byte[] bytes =
        ClimberFeatureRecord.build(
            TestEvents.USER_A,
            result.featureAsOf().toEpochMilli(),
            W.toEpochMilli(),
            result.checksum(),
            canonical,
            Instant.parse("2026-10-18T03:00:00.123Z"));
    Emitted emitted = Emitted.of(KeyedBytes.of(TestEvents.USER_A, bytes));

    assertThat(emitted.aggregatesText()).isEqualTo(canonical);
    assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("\"aggregates\":" + canonical);
    assertThat(Jcs.canonicalize(emitted.aggregates())).isEqualTo(canonical);
    assertThat(emitted.checksum()).isEqualTo(result.checksum());
    assertThat(emitted.featureAsOf()).isEqualTo(W.minusMillis(250));
    assertThat(emitted.sourceWatermark()).isEqualTo(W);
    assertThat(emitted.json().get("produced_at").asString()).isEqualTo("2026-10-18T03:00:00.123Z");
    assertThat(emitted.json().get("producer").asString()).isEqualTo("stream");
    assertThat(emitted.json().get("feature_schema_version").asString())
        .isEqualTo("climber-features.v1");
  }
}
