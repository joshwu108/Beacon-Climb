package com.beaconclimb.api.catalog;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read side of the catalog (BC-0106). Every statement is parameterised; the route list is one
 * keyset query (filters are appended as predicates, values are always bound), boards are one query
 * plus one for all their holds, and a route detail is one query plus one for its placements. A
 * variant is visible only when it is ACTIVE and its route is inside its activity window.
 */
@Repository
public class CatalogQueryRepository {

  /** One {@code boards} row; holds are attached by {@link #listBoards()}. */
  public record BoardRow(
      String boardId,
      String provider,
      String layoutId,
      String sizeId,
      boolean synthetic,
      String geometryJson,
      String availableAnglesJson,
      List<HoldRow> holds) {}

  public record HoldRow(String holdId, BigDecimal x, BigDecimal y) {}

  /** One visible route variant joined with its route. {@code tagsJson} is the raw column. */
  public record VariantRow(
      UUID variantId,
      String providerRouteUuid,
      String boardId,
      int angleDeg,
      String name,
      BigDecimal gradeNumeric,
      int gradeObservationCount,
      String tagsJson) {}

  public record PlacementRow(String holdId, BigDecimal x, BigDecimal y, String role) {}

  private static final String VARIANT_COLUMNS =
      "SELECT v.variant_id, r.provider_route_uuid, r.board_id, v.angle_deg, r.name,"
          + " v.grade_numeric, v.grade_observation_count, r.tags_json::text AS tags_json"
          + " FROM route_variants v JOIN routes r ON r.route_id = v.route_id";
  private static final String VISIBLE =
      " v.validation_status = 'ACTIVE'"
          + " AND (r.active_from IS NULL OR r.active_from <= :now)"
          + " AND (r.active_to IS NULL OR r.active_to > :now)";

  private final JdbcClient jdbc;

  public CatalogQueryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public boolean boardExists(String boardId) {
    return jdbc.sql("SELECT 1 FROM boards WHERE board_id = :id")
        .param("id", boardId)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  /** All boards with their holds, ordered by board id then provider hold id. */
  public List<BoardRow> listBoards() {
    Map<String, List<HoldRow>> holds = new LinkedHashMap<>();
    List<BoardRow> boards =
        jdbc.sql(
                "SELECT board_id, provider, layout_id, size_id, synthetic, geometry_json::text,"
                    + " available_angles_json::text FROM boards ORDER BY board_id")
            .query(
                (rs, rowNum) -> {
                  List<HoldRow> own = new ArrayList<>();
                  holds.put(rs.getString("board_id"), own);
                  return new BoardRow(
                      rs.getString("board_id"),
                      rs.getString("provider"),
                      rs.getString("layout_id"),
                      rs.getString("size_id"),
                      rs.getBoolean("synthetic"),
                      rs.getString("geometry_json"),
                      rs.getString("available_angles_json"),
                      own);
                })
            .list();
    jdbc.sql(
            "SELECT board_id, provider_hold_id, x, y FROM holds"
                + " ORDER BY board_id, provider_hold_id")
        .query(
            rs -> {
              List<HoldRow> own = holds.get(rs.getString("board_id"));
              if (own != null) {
                own.add(
                    new HoldRow(
                        rs.getString("provider_hold_id"),
                        rs.getBigDecimal("x"),
                        rs.getBigDecimal("y")));
              }
            });
    return boards;
  }

  /**
   * Up to {@code limit + 1} visible variants after the cursor; the extra row signals a next page.
   */
  public List<VariantRow> listRoutes(RouteQuery query, Instant now) {
    StringBuilder sql = new StringBuilder(VARIANT_COLUMNS).append(" WHERE").append(VISIBLE);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("now", timestamp(now));
    if (query.boardId() != null) {
      sql.append(" AND r.board_id = :board_id");
      params.put("board_id", query.boardId());
    }
    if (query.angleDeg() != null) {
      sql.append(" AND v.angle_deg = :angle");
      params.put("angle", query.angleDeg());
    }
    if (query.gradeMin() != null) {
      sql.append(" AND v.grade_numeric >= :grade_min");
      params.put("grade_min", query.gradeMin());
    }
    if (query.gradeMax() != null) {
      sql.append(" AND v.grade_numeric <= :grade_max");
      params.put("grade_max", query.gradeMax());
    }
    appendCursor(query.cursor(), sql, params);
    sql.append(" ORDER BY v.grade_numeric ASC NULLS LAST, v.variant_id ASC LIMIT :limit");
    params.put("limit", query.limit() + 1);
    return jdbc.sql(sql.toString()).params(params).query(CatalogQueryRepository::variant).list();
  }

  /** Keyset predicate matching the {@code (grade_numeric ASC NULLS LAST, variant_id ASC)} order. */
  private static void appendCursor(
      RouteCursor cursor, StringBuilder sql, Map<String, Object> params) {
    if (cursor == null) {
      return;
    }
    params.put("cursor_id", cursor.variantId());
    if (cursor.gradeNumeric() == null) {
      sql.append(" AND v.grade_numeric IS NULL AND v.variant_id > :cursor_id");
      return;
    }
    params.put("cursor_grade", cursor.gradeNumeric());
    sql.append(
        " AND (v.grade_numeric > :cursor_grade"
            + " OR (v.grade_numeric = :cursor_grade AND v.variant_id > :cursor_id)"
            + " OR v.grade_numeric IS NULL)");
  }

  /**
   * The visible variant with this public id. {@code provider_route_uuid} is unique per board only,
   * so if two boards ever share one the lowest board id wins deterministically.
   */
  public Optional<VariantRow> findVisibleVariant(RouteVariantId id, Instant now) {
    return jdbc.sql(
            VARIANT_COLUMNS
                + " WHERE r.provider_route_uuid = :uuid AND v.angle_deg = :angle AND"
                + VISIBLE
                + " ORDER BY r.board_id LIMIT 1")
        .param("uuid", id.providerRouteUuid())
        .param("angle", id.angleDeg())
        .param("now", timestamp(now))
        .query(CatalogQueryRepository::variant)
        .optional();
  }

  /** Placements in setter order; ties and missing orders fall back to hold id then role. */
  public List<PlacementRow> placements(UUID variantId) {
    return jdbc.sql(
            "SELECT h.provider_hold_id, h.x, h.y, p.role FROM route_placements p"
                + " JOIN holds h ON h.hold_id = p.hold_id WHERE p.variant_id = :id"
                + " ORDER BY p.placement_order ASC NULLS LAST, h.provider_hold_id, p.role")
        .param("id", variantId)
        .query(
            (rs, rowNum) ->
                new PlacementRow(
                    rs.getString("provider_hold_id"),
                    rs.getBigDecimal("x"),
                    rs.getBigDecimal("y"),
                    rs.getString("role")))
        .list();
  }

  private static VariantRow variant(ResultSet rs, int rowNum) throws SQLException {
    return new VariantRow(
        rs.getObject("variant_id", UUID.class),
        rs.getString("provider_route_uuid"),
        rs.getString("board_id"),
        rs.getInt("angle_deg"),
        rs.getString("name"),
        rs.getBigDecimal("grade_numeric"),
        rs.getInt("grade_observation_count"),
        rs.getString("tags_json"));
  }
}
