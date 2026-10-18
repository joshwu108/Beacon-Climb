package com.beaconclimb.api.materializer;

import com.beaconclimb.features.FeatureAggregates;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;

/**
 * One validated climber-features.v1 record (contracts/events/climber-features.v1.schema.json) as
 * the materializer needs it. The scalar members come from the document the shared validator already
 * parsed; the {@code aggregates} member is cut out of the original bytes by offset ({@link
 * #aggregatesText}) rather than re-serialised from the tree, so the text stored in {@code
 * aggregates_json} is the producer's canonical JCS text byte for byte (the stream job embeds {@code
 * FeatureAggregates.toJson()} unchanged; the parity oracle compares that text). Postgres {@code
 * jsonb} normalises on storage either way; what this guarantees is that nothing on the api side can
 * re-spell a number or reorder a key before the value reaches the database.
 *
 * @param eventId the record's {@code event_id}
 * @param userId the keyed user
 * @param featureAsOf {@code feature_as_of}
 * @param sourceWatermark {@code source_watermark}, stored as {@code cutoff_w}
 * @param provenanceChecksum {@code provenance_checksum}
 * @param aggregatesJson the {@code aggregates} member text exactly as received
 */
public record FeatureEvent(
    UUID eventId,
    UUID userId,
    Instant featureAsOf,
    Instant sourceWatermark,
    String provenanceChecksum,
    String aggregatesJson) {

  /** {@code feature_schema_version} "climber-features.v1" is the storage row's version 1. */
  public static final int FEATURE_SCHEMA_VERSION = FeatureAggregates.SCHEMA_VERSION;

  static final String AGGREGATES = "aggregates";
  private static final JsonFactory FACTORY = JsonFactory.builder().build();

  /** Builds the event from the validated document and the bytes it was parsed from. */
  public static FeatureEvent from(byte[] bytes, JsonNode payload) {
    return new FeatureEvent(
        UUID.fromString(payload.get("event_id").asString()),
        UUID.fromString(payload.get("user_id").asString()),
        Instant.parse(payload.get("feature_as_of").asString()),
        Instant.parse(payload.get("source_watermark").asString()),
        payload.get("provenance_checksum").asString(),
        aggregatesText(bytes));
  }

  /**
   * The text of the top-level {@code aggregates} object inside {@code bytes}, located by token byte
   * offsets with a streaming parser. The schema guarantees exactly one such member of type object;
   * anything else is a programming error, not a data error.
   */
  static String aggregatesText(byte[] bytes) {
    try (JsonParser parser = FACTORY.createParser(ObjectReadContext.empty(), bytes)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        throw new IllegalStateException("feature record is not a JSON object");
      }
      while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
        String name = parser.currentName();
        JsonToken value = parser.nextToken();
        if (AGGREGATES.equals(name) && value == JsonToken.START_OBJECT) {
          long start = parser.currentTokenLocation().getByteOffset();
          parser.skipChildren();
          long end = parser.currentTokenLocation().getByteOffset() + 1;
          return new String(bytes, (int) start, (int) (end - start), StandardCharsets.UTF_8);
        }
        if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
          parser.skipChildren();
        }
      }
    }
    throw new IllegalStateException("feature record has no aggregates object");
  }
}
