package com.beaconclimb.api.materializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.platform.json.Jcs;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The {@code aggregates} member is cut from the original bytes, not re-serialised. */
class FeatureEventTest {

  @Test
  void aggregatesTextIsTheRawMemberBytes() {
    String aggregates =
        "{\"attempts_30d\":4,\"attempts_7d\":2,\"max_sent_grade_numeric_90d\":20.5,"
            + "\"recent_variant_ids\":[\"a:40\",\"b:40\"],\"send_rate_by_grade_band_90d\":"
            + "{\"INTERMEDIATE\":{\"attempts\":3,\"sends\":1}}}";
    String record =
        "{\"schema_version\":1,\"user_id\":\"u\",\"nested\":{\"aggregates\":{\"x\":1}},"
            + "\"list\":[{\"aggregates\":0}],\"aggregates\":"
            + aggregates
            + ",\"producer\":\"stream\"}";

    String text = FeatureEvent.aggregatesText(record.getBytes(StandardCharsets.UTF_8));

    assertThat(text).isEqualTo(aggregates);
    assertThat(Jcs.canonicalize(text)).isEqualTo(aggregates);
  }

  @Test
  void aggregatesTextSurvivesMultiByteCharactersBeforeTheMember() {
    String record = "{\"user_id\":\"üñí☃\",\"aggregates\":{\"k\":\"v\"}}";

    String text = FeatureEvent.aggregatesText(record.getBytes(StandardCharsets.UTF_8));

    assertThat(text).isEqualTo("{\"k\":\"v\"}");
  }

  @Test
  void missingAggregatesObjectIsAProgrammingError() {
    byte[] bytes = "{\"aggregates\":[1,2]}".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> FeatureEvent.aggregatesText(bytes))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no aggregates object");
  }
}
