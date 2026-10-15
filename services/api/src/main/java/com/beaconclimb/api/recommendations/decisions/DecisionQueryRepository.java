package com.beaconclimb.api.recommendations.decisions;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.instant;

import com.beaconclimb.api.catalog.CatalogQueryRepository.VariantRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads for {@code getDecision} (BC-0112). The decision query carries the owner's {@code user_id}
 * and {@code erased = false} in the same predicate (TM-01), so a foreign or erased decision is
 * indistinguishable from a missing one. Neither the snapshot URI nor the digest is selected.
 */
@Repository
public class DecisionQueryRepository {

  /**
   * The columns of one owned decision; {@code requestJson} and {@code recordJson} are JSON text.
   */
  public record StoredDecision(
      UUID decisionId,
      Instant createdAt,
      String policyVersion,
      String modelVersion,
      boolean coldStart,
      boolean fallbackUsed,
      String requestJson,
      String recordJson) {}

  /** One served position as written by BC-0111. */
  public record ExposureRow(
      int position, String routeVariantId, double rankScore, List<String> reasonCodes) {}

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public DecisionQueryRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<StoredDecision> findOwned(UUID userId, UUID decisionId) {
    return jdbc.sql(
            "SELECT decision_id, created_at, policy_version, model_version, cold_start,"
                + " fallback_used, request_json::text AS request_json,"
                + " record_json::text AS record_json FROM recommendation_decisions"
                + " WHERE user_id = :user_id AND decision_id = :id AND erased = false")
        .param("user_id", userId)
        .param("id", decisionId)
        .query(DecisionQueryRepository::decision)
        .optional();
  }

  public List<ExposureRow> exposures(UUID decisionId) {
    return jdbc.sql(
            "SELECT position, route_variant_id, rank_score, reason_codes_json::text AS reasons"
                + " FROM recommendation_exposures WHERE decision_id = :id ORDER BY position")
        .param("id", decisionId)
        .query(this::exposure)
        .list();
  }

  /** Current catalog rows (any status) of the board's variants at the angle, by public id. */
  public Map<String, VariantRow> variants(
      String boardId, int angleDeg, Collection<String> providerRouteUuids) {
    Map<String, VariantRow> rows = new LinkedHashMap<>();
    if (providerRouteUuids.isEmpty()) {
      return rows;
    }
    jdbc.sql(
            "SELECT v.variant_id, r.provider_route_uuid, r.board_id, v.angle_deg, r.name,"
                + " v.grade_numeric, v.grade_observation_count FROM route_variants v"
                + " JOIN routes r ON r.route_id = v.route_id WHERE r.board_id = :board"
                + " AND v.angle_deg = :angle AND r.provider_route_uuid IN (:uuids)")
        .param("board", boardId)
        .param("angle", angleDeg)
        .param("uuids", List.copyOf(providerRouteUuids))
        .query(
            rs -> {
              VariantRow row =
                  new VariantRow(
                      rs.getObject("variant_id", UUID.class),
                      rs.getString("provider_route_uuid"),
                      rs.getString("board_id"),
                      rs.getInt("angle_deg"),
                      rs.getString("name"),
                      rs.getBigDecimal("grade_numeric"),
                      rs.getInt("grade_observation_count"),
                      null);
              rows.put(row.providerRouteUuid() + ":" + row.angleDeg(), row);
            });
    return rows;
  }

  private static StoredDecision decision(ResultSet rs, int rowNum) throws SQLException {
    return new StoredDecision(
        rs.getObject("decision_id", UUID.class),
        instant(rs, "created_at"),
        rs.getString("policy_version"),
        rs.getString("model_version"),
        rs.getBoolean("cold_start"),
        rs.getBoolean("fallback_used"),
        rs.getString("request_json"),
        rs.getString("record_json"));
  }

  private ExposureRow exposure(ResultSet rs, int rowNum) throws SQLException {
    return new ExposureRow(
        rs.getInt("position"),
        rs.getString("route_variant_id"),
        rs.getDouble("rank_score"),
        json.readValue(rs.getString("reasons"), STRING_LIST));
  }
}
