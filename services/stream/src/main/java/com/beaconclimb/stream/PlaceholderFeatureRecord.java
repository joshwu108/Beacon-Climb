package com.beaconclimb.stream;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * BC-0303 PLACEHOLDER: a climber-features.v1 record
 * (contracts/events/climber-features.v1.schema.json) whose aggregates are those of the EMPTY
 * attempt set, so the end-to-end Kafka round trip can be proven before the real state-backed
 * computation (BC-0304, shared FeatureComputer) replaces {@link PlaceholderFeatures}. Every record
 * carries the checksum of the empty set, never a value derived from the event, so no consumer can
 * mistake it for a real snapshot.
 */
public final class PlaceholderFeatureRecord {

  public static final String PRODUCER = "stream";
  public static final String FEATURE_SCHEMA_VERSION = "climber-features.v1";

  /** sha256 of the empty provenance text (FeatureComputer.provenanceChecksum of no rows). */
  public static final String EMPTY_SET_CHECKSUM = sha256Hex(new byte[0]);

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private PlaceholderFeatureRecord() {}

  /**
   * @param userId the keyed user
   * @param featureAsOfMillis the event's occurred_at (max occurred_at considered, ADR-0004)
   * @param sourceWatermarkMillis the cutoff W = max(current watermark, occurred_at)
   * @param producedAt wall-clock production time
   */
  public static byte[] build(
      String userId, long featureAsOfMillis, long sourceWatermarkMillis, Instant producedAt) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("schema_version", 1);
    node.put("event_id", Uuid7.next().toString());
    node.put("user_id", userId);
    node.put("feature_schema_version", FEATURE_SCHEMA_VERSION);
    node.put("feature_as_of", iso(featureAsOfMillis));
    node.put("source_watermark", iso(sourceWatermarkMillis));
    node.put("provenance_checksum", EMPTY_SET_CHECKSUM);
    ObjectNode aggregates = node.putObject("aggregates");
    aggregates.put("attempts_7d", 0);
    aggregates.put("attempts_30d", 0);
    aggregates.put("attempts_90d", 0);
    aggregates.put("sends_7d", 0);
    aggregates.put("sends_30d", 0);
    aggregates.put("sends_90d", 0);
    aggregates.putObject("send_rate_by_grade_band_90d");
    aggregates.putArray("recent_variant_ids");
    aggregates.putNull("last_session_at");
    aggregates.putNull("max_sent_grade_numeric_90d");
    aggregates.put("distinct_sessions_30d", 0);
    node.put("produced_at", DateTimeFormatter.ISO_INSTANT.format(producedAt));
    node.put("producer", PRODUCER);
    return MAPPER.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
  }

  static String iso(long epochMillis) {
    return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis));
  }

  static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
