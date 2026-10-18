package com.beaconclimb.stream.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.stream.KeyedBytes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One emitted climber-features.v1 record, schema-validated on construction (format assertions on)
 * and decoded for assertions. {@link #aggregatesText()} is the exact substring of the value bytes,
 * not a re-serialisation, so byte-level parity with {@code FeatureAggregates.toJson()} is checked
 * on what Kafka would carry.
 */
record Emitted(
    KeyedBytes record,
    JsonNode json,
    String userId,
    Instant featureAsOf,
    Instant sourceWatermark,
    String checksum,
    String aggregatesText) {

  private static final ClimberFeaturesSchema SCHEMA = new ClimberFeaturesSchema();
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String AGGREGATES_MEMBER = "\"aggregates\":";

  static Emitted of(KeyedBytes record) {
    String text = new String(record.value, StandardCharsets.UTF_8);
    JsonNode json = MAPPER.readTree(text);
    assertThat(SCHEMA.validate(json)).as(text).isEmpty();
    assertThat(record.key).isEqualTo(json.get("user_id").asString());
    return new Emitted(
        record,
        json,
        json.get("user_id").asString(),
        Instant.parse(json.get("feature_as_of").asString()),
        Instant.parse(json.get("source_watermark").asString()),
        json.get("provenance_checksum").asString(),
        embeddedAggregates(text));
  }

  static List<Emitted> all(List<KeyedBytes> records) {
    return records.stream().map(Emitted::of).toList();
  }

  JsonNode aggregates() {
    return json.get("aggregates");
  }

  int count(String key) {
    return aggregates().get(key).asInt();
  }

  /** The raw text between {@code "aggregates":} and the next top-level member. */
  private static String embeddedAggregates(String text) {
    int start = text.indexOf(AGGREGATES_MEMBER);
    assertThat(start).as("aggregates member in %s", text).isPositive();
    int from = start + AGGREGATES_MEMBER.length();
    int depth = 0;
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return text.substring(from, i + 1);
        }
      }
    }
    throw new AssertionError("unterminated aggregates object in " + text);
  }
}
