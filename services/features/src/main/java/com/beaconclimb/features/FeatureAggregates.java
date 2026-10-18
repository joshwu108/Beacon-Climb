package com.beaconclimb.features;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The climber-features.v1 aggregate payload (contracts/features/climber-features.v1.md): exactly
 * the eleven keys of {@code aggregates} in contracts/events/climber-features.v1.schema.json, which
 * are also the user_features keys of the decision record minus the policy-derived {@code
 * target_grade_numeric}. {@link #toJson()} is the RFC 8785 canonical text, so equal aggregates
 * always serialise to identical bytes.
 */
public record FeatureAggregates(
    int attempts7d,
    int attempts30d,
    int attempts90d,
    int sends7d,
    int sends30d,
    int sends90d,
    SortedMap<String, BandCounts> sendRateByGradeBand90d,
    List<String> recentVariantIds,
    Instant lastSessionAt,
    Double maxSentGradeNumeric90d,
    int distinctSessions30d) {

  /** Attempt records and sends in one grade band over the 90-day window. */
  public record BandCounts(int attempts, int sends) {}

  public static final int SCHEMA_VERSION = 1;
  public static final int RECENT_VARIANTS_LIMIT = 20;
  public static final List<String> KEYS =
      List.of(
          "attempts_7d",
          "attempts_30d",
          "attempts_90d",
          "sends_7d",
          "sends_30d",
          "sends_90d",
          "send_rate_by_grade_band_90d",
          "recent_variant_ids",
          "last_session_at",
          "max_sent_grade_numeric_90d",
          "distinct_sessions_30d");

  public FeatureAggregates {
    sendRateByGradeBand90d =
        Collections.unmodifiableSortedMap(new TreeMap<>(sendRateByGradeBand90d));
    recentVariantIds = List.copyOf(recentVariantIds);
  }

  /** Canonical (JCS) JSON text: the stored {@code aggregates_json} and the parity payload. */
  public String toJson() {
    return Jcs.canonicalize(toJsonNode());
  }

  public JsonNode toJsonNode() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("attempts_7d", attempts7d);
    node.put("attempts_30d", attempts30d);
    node.put("attempts_90d", attempts90d);
    node.put("sends_7d", sends7d);
    node.put("sends_30d", sends30d);
    node.put("sends_90d", sends90d);
    ObjectNode bands = node.putObject("send_rate_by_grade_band_90d");
    for (Map.Entry<String, BandCounts> entry : sendRateByGradeBand90d.entrySet()) {
      ObjectNode counts = bands.putObject(entry.getKey());
      counts.put("attempts", entry.getValue().attempts());
      counts.put("sends", entry.getValue().sends());
    }
    ArrayNode recent = node.putArray("recent_variant_ids");
    recentVariantIds.forEach(recent::add);
    if (lastSessionAt == null) {
      node.putNull("last_session_at");
    } else {
      node.put("last_session_at", Timestamps.format(lastSessionAt));
    }
    if (maxSentGradeNumeric90d == null) {
      node.putNull("max_sent_grade_numeric_90d");
    } else {
      node.put("max_sent_grade_numeric_90d", maxSentGradeNumeric90d);
    }
    node.put("distinct_sessions_30d", distinctSessions30d);
    return node;
  }

  /** Inverse of {@link #toJsonNode()} for rows read back from {@code user_feature_snapshots}. */
  public static FeatureAggregates fromJson(JsonNode node) {
    SortedMap<String, BandCounts> bands = new TreeMap<>();
    for (Map.Entry<String, JsonNode> entry : node.get("send_rate_by_grade_band_90d").properties()) {
      bands.put(
          entry.getKey(),
          new BandCounts(
              entry.getValue().get("attempts").asInt(), entry.getValue().get("sends").asInt()));
    }
    List<String> recent = new ArrayList<>();
    node.get("recent_variant_ids").forEach(id -> recent.add(id.asString()));
    JsonNode lastSession = node.get("last_session_at");
    JsonNode maxSent = node.get("max_sent_grade_numeric_90d");
    return new FeatureAggregates(
        node.get("attempts_7d").asInt(),
        node.get("attempts_30d").asInt(),
        node.get("attempts_90d").asInt(),
        node.get("sends_7d").asInt(),
        node.get("sends_30d").asInt(),
        node.get("sends_90d").asInt(),
        bands,
        recent,
        lastSession.isNull() ? null : Instant.parse(lastSession.asString()),
        maxSent.isNull() ? null : maxSent.doubleValue(),
        node.get("distinct_sessions_30d").asInt());
  }
}
