package com.beaconclimb.api.recommendations;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.instant;
import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import com.beaconclimb.api.catalog.CatalogQueryRepository.VariantRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
 * The three reads of a recommendation request (BC-0111), each one parameterised statement: the
 * board with its angles and catalog version, the candidate set, and the user's last ACTIVE attempt
 * per variant (the cooldown input). A candidate is a visible ACTIVE variant of the board at the
 * angle that the decision contract can record: {@code CandidateSnapshot.candidate.features}
 * requires a numeric {@code grade_numeric} and {@code hold_count >= 1}, so an ungraded variant or
 * one without placements is not a candidate (documented deviation from "all visible variants"; the
 * snapshot could not be written faithfully for it). Candidates come back in public-id order so the
 * snapshot bytes of equal inputs are equal.
 */
@Repository
public class CandidateRepository {

  /** A board's angles and the version of the catalog file it was imported from. */
  public record BoardInfo(String boardId, List<Integer> availableAngles, String catalogVersion) {}

  /** One candidate: its catalog row plus the snapshot features the ranker does not read. */
  public record CandidateRow(VariantRow variant, int holdCount, List<String> tags) {}

  private static final TypeReference<List<Integer>> INT_LIST = new TypeReference<>() {};
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private static final String CANDIDATES =
      "SELECT v.variant_id, r.provider_route_uuid, r.board_id, v.angle_deg, r.name,"
          + " v.grade_numeric, v.grade_observation_count, r.tags_json::text AS tags_json,"
          + " (SELECT count(DISTINCT p.hold_id) FROM route_placements p"
          + " WHERE p.variant_id = v.variant_id) AS hold_count"
          + " FROM route_variants v JOIN routes r ON r.route_id = v.route_id"
          + " WHERE r.board_id = :board AND v.angle_deg = :angle"
          + " AND v.validation_status = 'ACTIVE' AND v.grade_numeric IS NOT NULL"
          + " AND (r.active_from IS NULL OR r.active_from <= :now)"
          + " AND (r.active_to IS NULL OR r.active_to > :now)"
          + " AND EXISTS (SELECT 1 FROM route_placements p WHERE p.variant_id = v.variant_id)"
          + " ORDER BY r.provider_route_uuid, v.variant_id";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public CandidateRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Empty when the board does not exist; {@code catalog_version} falls back to the file hash. */
  public Optional<BoardInfo> board(String boardId) {
    return jdbc.sql(
            "SELECT b.board_id, b.available_angles_json::text AS angles,"
                + " COALESCE(p.version, p.content_sha256) AS catalog_version"
                + " FROM boards b JOIN provenance_sources p ON p.provenance_id = b.provenance_id"
                + " WHERE b.board_id = :id")
        .param("id", boardId)
        .query(
            (rs, rowNum) ->
                new BoardInfo(
                    rs.getString("board_id"),
                    json.readValue(rs.getString("angles"), INT_LIST),
                    rs.getString("catalog_version")))
        .optional();
  }

  public List<CandidateRow> candidates(String boardId, int angleDeg, Instant now) {
    return jdbc.sql(CANDIDATES)
        .param("board", boardId)
        .param("angle", angleDeg)
        .param("now", timestamp(now))
        .query(this::candidate)
        .list();
  }

  /** Public id to the latest {@code occurred_at} of the user's ACTIVE attempts at the angle. */
  public Map<String, Instant> lastAttemptedAt(UUID userId, String boardId, int angleDeg) {
    Map<String, Instant> last = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT a.route_variant_public_id, max(a.occurred_at) AS last_at FROM attempts a"
                + " JOIN route_variants v ON v.variant_id = a.variant_id"
                + " JOIN routes r ON r.route_id = v.route_id"
                + " WHERE a.user_id = :user_id AND a.status = 'ACTIVE'"
                + " AND r.board_id = :board AND v.angle_deg = :angle"
                + " GROUP BY a.route_variant_public_id")
        .param("user_id", userId)
        .param("board", boardId)
        .param("angle", angleDeg)
        .query(
            rs -> {
              last.put(rs.getString("route_variant_public_id"), instant(rs, "last_at"));
            });
    return last;
  }

  private CandidateRow candidate(ResultSet rs, int rowNum) throws SQLException {
    VariantRow variant =
        new VariantRow(
            rs.getObject("variant_id", UUID.class),
            rs.getString("provider_route_uuid"),
            rs.getString("board_id"),
            rs.getInt("angle_deg"),
            rs.getString("name"),
            rs.getBigDecimal("grade_numeric"),
            rs.getInt("grade_observation_count"),
            null);
    return new CandidateRow(
        variant, rs.getInt("hold_count"), json.readValue(rs.getString("tags_json"), STRING_LIST));
  }
}
