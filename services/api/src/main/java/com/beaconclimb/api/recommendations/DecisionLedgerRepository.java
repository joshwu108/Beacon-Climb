package com.beaconclimb.api.recommendations;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import com.beaconclimb.api.features.FeatureFallbackReason;
import com.beaconclimb.api.features.FeatureSource;
import com.beaconclimb.api.ranking.Ranked;
import com.beaconclimb.api.ranking.ReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only writes to {@code recommendation_decisions} and {@code recommendation_exposures}
 * (BC-0111, V6). The caller wraps {@link #insertDecision} and {@link #insertExposures} in one
 * transaction; the runtime role holds no UPDATE or DELETE on either table (TM-24).
 */
@Repository
public class DecisionLedgerRepository {

  /**
   * The scalar projection of one decision record plus the two JSON documents. {@code featureSource}
   * / {@code featureFallbackReason} (V11, BC-0310 part 2) say which snapshot row the ranking read
   * and, for SYNC, why the STREAM row was not used; they are not part of {@code recordJson}
   * (decision-record.v1 is closed).
   */
  public record LedgerEntry(
      UUID decisionId,
      UUID userId,
      UUID sessionId,
      Instant requestedAt,
      String policyVersion,
      String policyCodeDigest,
      String modelVersion,
      UUID featureSnapshotUserId,
      int featureSchemaVersion,
      Instant featureAsOf,
      Instant featureCutoffW,
      FeatureSource featureSource,
      FeatureFallbackReason featureFallbackReason,
      String candidateSetDigest,
      String candidateSnapshotUri,
      String rngSeed,
      String environmentDigest,
      String requestJson,
      String recordJson,
      boolean coldStart,
      boolean fallbackUsed) {}

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public DecisionLedgerRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void insertDecision(LedgerEntry entry) {
    jdbc.sql(
            "INSERT INTO recommendation_decisions (decision_id, user_id, session_id, requested_at,"
                + " policy_version, policy_code_digest, model_version, feature_snapshot_user_id,"
                + " feature_schema_version, feature_as_of, feature_cutoff_w, feature_source,"
                + " feature_fallback_reason, candidate_set_digest,"
                + " candidate_snapshot_uri, rng_seed, environment_digest, request_json,"
                + " record_json, cold_start, fallback_used, erased, created_at)"
                + " VALUES (:id, :user_id, :session_id, :requested_at, :policy_version,"
                + " :policy_code_digest, :model_version, :feature_user_id, :feature_schema,"
                + " :feature_as_of, :feature_cutoff_w, :feature_source, :feature_fallback_reason,"
                + " :candidate_set_digest, :snapshot_uri,"
                + " :rng_seed, :environment_digest, CAST(:request AS jsonb),"
                + " CAST(:record AS jsonb), :cold_start, :fallback_used, false, :requested_at)")
        .param("id", entry.decisionId())
        .param("user_id", entry.userId())
        .param("session_id", entry.sessionId())
        .param("requested_at", timestamp(entry.requestedAt()))
        .param("policy_version", entry.policyVersion())
        .param("policy_code_digest", entry.policyCodeDigest())
        .param("model_version", entry.modelVersion())
        .param("feature_user_id", entry.featureSnapshotUserId())
        .param("feature_schema", entry.featureSchemaVersion())
        .param("feature_as_of", timestamp(entry.featureAsOf()))
        .param("feature_cutoff_w", timestamp(entry.featureCutoffW()))
        .param("feature_source", entry.featureSource().name())
        .param(
            "feature_fallback_reason",
            entry.featureFallbackReason() == null ? null : entry.featureFallbackReason().name())
        .param("candidate_set_digest", entry.candidateSetDigest())
        .param("snapshot_uri", entry.candidateSnapshotUri())
        .param("rng_seed", entry.rngSeed())
        .param("environment_digest", entry.environmentDigest())
        .param("request", entry.requestJson())
        .param("record", entry.recordJson())
        .param("cold_start", entry.coldStart())
        .param("fallback_used", entry.fallbackUsed())
        .update();
  }

  /** Decisions this user created at or after {@code since} (BC-0123 F5 daily quota). */
  public long countSince(UUID userId, Instant since) {
    return jdbc.sql(
            "SELECT count(*) FROM recommendation_decisions"
                + " WHERE user_id = :user_id AND created_at >= :since")
        .param("user_id", userId)
        .param("since", timestamp(since))
        .query(Long.class)
        .single();
  }

  public void insertExposures(UUID decisionId, List<Ranked> ranked) {
    for (Ranked item : ranked) {
      jdbc.sql(
              "INSERT INTO recommendation_exposures (decision_id, position, route_variant_id,"
                  + " rank_score, reason_codes_json) VALUES (:id, :position, :variant, :score,"
                  + " CAST(:reasons AS jsonb))")
          .param("id", decisionId)
          .param("position", item.position())
          .param("variant", item.routeVariantId())
          .param("score", item.score())
          .param(
              "reasons",
              json.writeValueAsString(item.reasonCodes().stream().map(ReasonCode::name).toList()))
          .update();
    }
  }
}
