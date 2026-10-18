package com.beaconclimb.stream.features;

import com.beaconclimb.events.Uuid7;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.util.RawValue;

/**
 * Serialises one climber-features.v1 record (contracts/events/climber-features.v1.schema.json). The
 * {@code aggregates} member is embedded as the canonical JCS text the shared {@code
 * FeatureAggregates.toJson()} produced, written through Jackson's {@link RawValue} so it is copied
 * byte for byte: no tree rebuild that could reorder keys or respell numbers sits between the
 * computer and the Kafka value (the parity oracle compares those bytes).
 */
public final class ClimberFeatureRecord {

  public static final String PRODUCER = "stream";
  public static final String FEATURE_SCHEMA_VERSION = "climber-features.v1";
  public static final int SCHEMA_VERSION = 1;

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ClimberFeatureRecord() {}

  /**
   * @param userId the keyed user
   * @param featureAsOfMillis max occurred_at over the included rows, or W when none is included
   * @param sourceWatermarkMillis the cutoff W = max(current watermark, occurred_at)
   * @param provenanceChecksum the shared recipe over the included rows
   * @param aggregatesJson the canonical aggregates text, embedded unchanged
   * @param producedAt the operator's processing time
   */
  public static byte[] build(
      String userId,
      long featureAsOfMillis,
      long sourceWatermarkMillis,
      String provenanceChecksum,
      String aggregatesJson,
      Instant producedAt) {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(provenanceChecksum, "provenanceChecksum");
    Objects.requireNonNull(aggregatesJson, "aggregatesJson");
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("schema_version", SCHEMA_VERSION);
    node.put("event_id", Uuid7.next().toString());
    node.put("user_id", userId);
    node.put("feature_schema_version", FEATURE_SCHEMA_VERSION);
    node.put("feature_as_of", iso(featureAsOfMillis));
    node.put("source_watermark", iso(sourceWatermarkMillis));
    node.put("provenance_checksum", provenanceChecksum);
    node.putRawValue("aggregates", new RawValue(aggregatesJson));
    node.put("produced_at", DateTimeFormatter.ISO_INSTANT.format(producedAt));
    node.put("producer", PRODUCER);
    return MAPPER.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
  }

  /** RFC 3339 UTC, fractional seconds only when non-zero (the contract's timestamp format). */
  public static String iso(long epochMillis) {
    return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
  }
}
