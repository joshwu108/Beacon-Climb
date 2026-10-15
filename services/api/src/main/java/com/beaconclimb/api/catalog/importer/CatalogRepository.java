package com.beaconclimb.api.catalog.importer;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import com.beaconclimb.api.catalog.importer.CatalogRecords.Board;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Hold;
import com.beaconclimb.api.catalog.importer.CatalogRecords.Manifest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Catalog upserts for the importer. Every statement is parameterised; multi-row statements bind
 * arrays and {@code unnest} them so a chunk is one round trip, and {@code RETURNING (xmax = 0)}
 * tells an insert from an update. Callers run inside the importer's transaction.
 */
@Repository
public class CatalogRepository {

  /** Outcome of one upserted row. */
  public record Upsert(UUID id, boolean inserted) {}

  /** Route rows to upsert: parallel arrays, one element per route. */
  record RouteRows(
      String[] ids,
      String[] boardIds,
      String[] uuids,
      String[] names,
      String[] setters,
      String[] tagsJson,
      String[] activeFrom,
      String[] activeTo) {}

  /** Variant rows to upsert: parallel arrays, one element per variant. */
  record VariantRows(
      String[] ids,
      String[] routeIds,
      String[] angles,
      String[] grades,
      String[] observationCounts,
      String[] statuses) {}

  /** Placement rows to insert: parallel arrays, one element per placement. */
  record PlacementRows(String[] variantIds, String[] holdIds, String[] roles, String[] orders) {}

  private final JdbcClient jdbc;

  public CatalogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Keyed by content_sha256: the same file content maps to the same provenance row. */
  public Upsert upsertProvenance(UUID id, Manifest manifest, Instant importedAt) {
    return jdbc.sql(
            "INSERT INTO provenance_sources (provenance_id, source, version, content_sha256,"
                + " synthetic, license_review_status, imported_at)"
                + " VALUES (:id, :source, :version, :sha, :synthetic, :license, :imported_at)"
                + " ON CONFLICT (content_sha256) DO UPDATE SET source = EXCLUDED.source,"
                + " version = EXCLUDED.version, synthetic = EXCLUDED.synthetic,"
                + " license_review_status = EXCLUDED.license_review_status"
                + " RETURNING provenance_id, (xmax = 0) AS inserted")
        .param("id", id)
        .param("source", manifest.source())
        .param("version", manifest.version())
        .param("sha", manifest.contentSha256())
        .param("synthetic", manifest.synthetic())
        .param("license", manifest.licenseReviewStatus())
        .param("imported_at", timestamp(importedAt))
        .query(
            (rs, rowNum) ->
                new Upsert(rs.getObject("provenance_id", UUID.class), rs.getBoolean("inserted")))
        .single();
  }

  public Upsert upsertBoard(Board board, String configJson, UUID provenanceId) {
    return jdbc.sql(
            "INSERT INTO boards (board_id, provider, layout_id, size_id, canonical_config_json,"
                + " synthetic, geometry_json, available_angles_json, provenance_id)"
                + " VALUES (:board_id, :provider, :layout_id, :size_id, CAST(:config AS jsonb),"
                + " :synthetic, CAST(:geometry AS jsonb), CAST(:angles AS jsonb), :provenance_id)"
                + " ON CONFLICT (board_id) DO UPDATE SET provider = EXCLUDED.provider,"
                + " layout_id = EXCLUDED.layout_id, size_id = EXCLUDED.size_id,"
                + " canonical_config_json = EXCLUDED.canonical_config_json,"
                + " synthetic = EXCLUDED.synthetic, geometry_json = EXCLUDED.geometry_json,"
                + " available_angles_json = EXCLUDED.available_angles_json,"
                + " provenance_id = EXCLUDED.provenance_id"
                + " RETURNING (xmax = 0) AS inserted")
        .param("board_id", board.boardId())
        .param("provider", board.provider())
        .param("layout_id", board.layoutId())
        .param("size_id", board.sizeId())
        .param("config", configJson)
        .param("synthetic", board.synthetic())
        .param("geometry", board.geometry().toString())
        .param("angles", board.availableAngles().toString())
        .param("provenance_id", provenanceId)
        .query((rs, rowNum) -> new Upsert(null, rs.getBoolean("inserted")))
        .single();
  }

  /** Upserts one chunk of holds; returns hold ids by provider_hold_id with their outcome. */
  public Map<String, Upsert> upsertHolds(String boardId, List<Hold> holds) {
    int n = holds.size();
    String[] ids = new String[n];
    String[] pids = new String[n];
    String[] xs = new String[n];
    String[] ys = new String[n];
    String[] metas = new String[n];
    for (int i = 0; i < n; i++) {
      Hold hold = holds.get(i);
      ids[i] = com.beaconclimb.api.identity.Uuid7.next().toString();
      pids[i] = hold.providerHoldId();
      xs[i] = hold.x().toPlainString();
      ys[i] = hold.y().toPlainString();
      metas[i] = hold.geometryMeta() == null ? null : hold.geometryMeta().toString();
    }
    Map<String, Upsert> out = new HashMap<>();
    jdbc.sql(
            "INSERT INTO holds (hold_id, board_id, provider_hold_id, x, y, geometry_meta_json)"
                + " SELECT CAST(u.id AS uuid), :board_id, u.pid, CAST(u.x AS numeric),"
                + " CAST(u.y AS numeric), CAST(u.meta AS jsonb)"
                + " FROM unnest(CAST(:ids AS text[]), CAST(:pids AS text[]), CAST(:xs AS text[]),"
                + " CAST(:ys AS text[]), CAST(:metas AS text[])) AS u(id, pid, x, y, meta)"
                + " ON CONFLICT (board_id, provider_hold_id) DO UPDATE SET x = EXCLUDED.x,"
                + " y = EXCLUDED.y, geometry_meta_json = EXCLUDED.geometry_meta_json"
                + " RETURNING hold_id, provider_hold_id, (xmax = 0) AS inserted")
        .param("board_id", boardId)
        .param("ids", ids)
        .param("pids", pids)
        .param("xs", xs)
        .param("ys", ys)
        .param("metas", metas)
        .query(
            rs -> {
              out.put(
                  rs.getString("provider_hold_id"),
                  new Upsert(rs.getObject("hold_id", UUID.class), rs.getBoolean("inserted")));
            });
    return out;
  }

  /** Upserts one chunk of routes; returns route ids keyed {@code boardId + NUL + uuid}. */
  public Map<String, Upsert> upsertRoutes(RouteRows rows, UUID provenanceId) {
    Map<String, Upsert> out = new HashMap<>();
    jdbc.sql(
            "INSERT INTO routes (route_id, board_id, provider_route_uuid, name, setter_pseudonym,"
                + " tags_json, active_from, active_to, provenance_id)"
                + " SELECT CAST(u.id AS uuid), u.board_id, u.uuid, u.name, u.setter,"
                + " CAST(u.tags AS jsonb), CAST(u.active_from AS timestamptz),"
                + " CAST(u.active_to AS timestamptz), :provenance_id"
                + " FROM unnest(CAST(:ids AS text[]), CAST(:board_ids AS text[]),"
                + " CAST(:uuids AS text[]), CAST(:names AS text[]), CAST(:setters AS text[]),"
                + " CAST(:tags AS text[]), CAST(:active_from AS text[]),"
                + " CAST(:active_to AS text[]))"
                + " AS u(id, board_id, uuid, name, setter, tags, active_from, active_to)"
                + " ON CONFLICT (board_id, provider_route_uuid) DO UPDATE SET"
                + " name = EXCLUDED.name, setter_pseudonym = EXCLUDED.setter_pseudonym,"
                + " tags_json = EXCLUDED.tags_json, active_from = EXCLUDED.active_from,"
                + " active_to = EXCLUDED.active_to, provenance_id = EXCLUDED.provenance_id"
                + " RETURNING route_id, board_id, provider_route_uuid, (xmax = 0) AS inserted")
        .param("provenance_id", provenanceId)
        .param("ids", rows.ids())
        .param("board_ids", rows.boardIds())
        .param("uuids", rows.uuids())
        .param("names", rows.names())
        .param("setters", rows.setters())
        .param("tags", rows.tagsJson())
        .param("active_from", rows.activeFrom())
        .param("active_to", rows.activeTo())
        .query(
            rs -> {
              out.put(
                  rs.getString("board_id") + "\u0000" + rs.getString("provider_route_uuid"),
                  new Upsert(rs.getObject("route_id", UUID.class), rs.getBoolean("inserted")));
            });
    return out;
  }

  /** Upserts one chunk of variants; returns variant ids keyed {@code routeId + ":" + angle}. */
  public Map<String, Upsert> upsertVariants(VariantRows rows) {
    Map<String, Upsert> out = new HashMap<>();
    jdbc.sql(
            "INSERT INTO route_variants (variant_id, route_id, angle_deg, grade_numeric,"
                + " grade_observation_count, validation_status)"
                + " SELECT CAST(u.id AS uuid), CAST(u.route_id AS uuid), CAST(u.angle AS smallint),"
                + " CAST(u.grade AS numeric), CAST(u.obs AS int), u.status"
                + " FROM unnest(CAST(:ids AS text[]), CAST(:route_ids AS text[]),"
                + " CAST(:angles AS text[]), CAST(:grades AS text[]), CAST(:obs AS text[]),"
                + " CAST(:statuses AS text[])) AS u(id, route_id, angle, grade, obs, status)"
                + " ON CONFLICT (route_id, angle_deg) DO UPDATE SET"
                + " grade_numeric = EXCLUDED.grade_numeric,"
                + " grade_observation_count = EXCLUDED.grade_observation_count,"
                + " validation_status = EXCLUDED.validation_status"
                + " RETURNING variant_id, route_id, angle_deg, (xmax = 0) AS inserted")
        .param("ids", rows.ids())
        .param("route_ids", rows.routeIds())
        .param("angles", rows.angles())
        .param("grades", rows.grades())
        .param("obs", rows.observationCounts())
        .param("statuses", rows.statuses())
        .query(
            rs -> {
              out.put(
                  rs.getString("route_id") + ":" + rs.getInt("angle_deg"),
                  new Upsert(rs.getObject("variant_id", UUID.class), rs.getBoolean("inserted")));
            });
    return out;
  }

  /** Placements are replaced per variant: delete, then insert the file's set. */
  public int deletePlacements(String[] variantIds) {
    return jdbc.sql("DELETE FROM route_placements WHERE variant_id = ANY(CAST(:ids AS uuid[]))")
        .param("ids", variantIds)
        .update();
  }

  public int insertPlacements(PlacementRows rows) {
    return jdbc.sql(
            "INSERT INTO route_placements (variant_id, hold_id, role, placement_order)"
                + " SELECT CAST(u.vid AS uuid), CAST(u.hid AS uuid), u.role, CAST(u.ord AS int)"
                + " FROM unnest(CAST(:vids AS text[]), CAST(:hids AS text[]),"
                + " CAST(:roles AS text[]), CAST(:orders AS text[])) AS u(vid, hid, role, ord)")
        .param("vids", rows.variantIds())
        .param("hids", rows.holdIds())
        .param("roles", rows.roles())
        .param("orders", rows.orders())
        .update();
  }
}
