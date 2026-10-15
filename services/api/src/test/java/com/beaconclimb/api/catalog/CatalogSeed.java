package com.beaconclimb.api.catalog;

import com.beaconclimb.api.catalog.importer.CatalogImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test data for the catalog endpoints: the committed SYNTHETIC demo board (300 routes, 609 variants
 * of which 19 are RETIRED, 144 holds) loaded through the real importer, plus four edge rows written
 * directly so every "must never be served" rule has a concrete witness: a RETIRED variant, an
 * INVALID variant, a route whose activity window ended and one whose window has not started. All
 * writes are idempotent so any test class can seed in any order.
 */
public final class CatalogSeed {

  public static final String BOARD_ID = "synthetic-demo-1";
  public static final Path DEMO_BOARD =
      Path.of("..", "..", "ml", "datasets", "synthetic", "fixtures", "demo-board-v1.jsonl")
          .toAbsolutePath()
          .normalize();
  public static final int EDGE_ANGLE = 40;
  public static final List<String> EDGE_ROUTE_UUIDS =
      List.of("test-retired", "test-invalid", "test-expired", "test-future");
  public static final String FIXTURE_RETIRED_ID = "syn-route-0015:40";

  private static final String EDGE_SOURCE = "test-edge-rows";
  private static final String EDGE_SHA = "e".repeat(64);
  private static final List<String> STATUSES = List.of("RETIRED", "INVALID", "ACTIVE", "ACTIVE");
  private static final OffsetDateTime FAR_PAST =
      OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime FAR_FUTURE =
      OffsetDateTime.of(2999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

  private CatalogSeed() {}

  /** Imports the demo board (a re-import only updates rows) and writes the edge rows. */
  public static void seed(CatalogImporter importer, JdbcClient jdbc) {
    try {
      Path reports = Files.createTempDirectory("catalog-seed");
      importer.importFile(DEMO_BOARD, false, reports);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    seedEdgeRows(jdbc);
  }

  static void seedEdgeRows(JdbcClient jdbc) {
    UUID provenance = edgeProvenance(jdbc);
    for (int i = 0; i < EDGE_ROUTE_UUIDS.size(); i++) {
      String uuid = EDGE_ROUTE_UUIDS.get(i);
      UUID routeId = UUID.nameUUIDFromBytes(("route:" + uuid).getBytes());
      UUID variantId = UUID.nameUUIDFromBytes(("variant:" + uuid).getBytes());
      OffsetDateTime activeFrom = uuid.equals("test-future") ? FAR_FUTURE : null;
      OffsetDateTime activeTo = uuid.equals("test-expired") ? FAR_PAST : null;
      jdbc.sql(
              "INSERT INTO routes (route_id, board_id, provider_route_uuid, name, tags_json,"
                  + " active_from, active_to, provenance_id) VALUES (:id, :board, :uuid, :name,"
                  + " '[]', :from, :to, :prov) ON CONFLICT (board_id, provider_route_uuid)"
                  + " DO NOTHING")
          .param("id", routeId)
          .param("board", BOARD_ID)
          .param("uuid", uuid)
          .param("name", "Edge " + uuid)
          .param("from", activeFrom)
          .param("to", activeTo)
          .param("prov", provenance)
          .update();
      jdbc.sql(
              "INSERT INTO route_variants (variant_id, route_id, angle_deg, grade_numeric,"
                  + " grade_observation_count, validation_status) SELECT :vid, route_id, :angle,"
                  + " 20.0, 1, :status FROM routes WHERE board_id = :board AND"
                  + " provider_route_uuid = :uuid ON CONFLICT (route_id, angle_deg) DO NOTHING")
          .param("vid", variantId)
          .param("angle", EDGE_ANGLE)
          .param("status", STATUSES.get(i))
          .param("board", BOARD_ID)
          .param("uuid", uuid)
          .update();
    }
  }

  /** The edge rows are test-authored, so they carry their own provenance, not the fixture's. */
  private static UUID edgeProvenance(JdbcClient jdbc) {
    jdbc.sql(
            "INSERT INTO provenance_sources (provenance_id, source, version, content_sha256,"
                + " synthetic, license_review_status, imported_at) VALUES (:id, :source, null,"
                + " :sha, true, 'SYNTHETIC', now()) ON CONFLICT (content_sha256) DO NOTHING")
        .param("id", UUID.nameUUIDFromBytes(EDGE_SOURCE.getBytes()))
        .param("source", EDGE_SOURCE)
        .param("sha", EDGE_SHA)
        .update();
    return jdbc.sql("SELECT provenance_id FROM provenance_sources WHERE content_sha256 = :sha")
        .param("sha", EDGE_SHA)
        .query(UUID.class)
        .single();
  }

  /** Public ids of every variant the endpoints must never return, computed from the tables. */
  public static Set<String> hiddenPublicIds(JdbcClient jdbc) {
    return new HashSet<>(
        jdbc.sql(
                "SELECT r.provider_route_uuid || ':' || v.angle_deg FROM route_variants v"
                    + " JOIN routes r ON r.route_id = v.route_id"
                    + " WHERE v.validation_status <> 'ACTIVE'"
                    + " OR (r.active_from IS NOT NULL AND r.active_from > now())"
                    + " OR (r.active_to IS NOT NULL AND r.active_to <= now())")
            .query(String.class)
            .list());
  }

  /** Count of variants the list endpoint must return for {@code boardId} (null = all boards). */
  public static int visibleCount(JdbcClient jdbc, String boardId) {
    return jdbc.sql(
            "SELECT count(*) FROM route_variants v JOIN routes r ON r.route_id = v.route_id"
                + " WHERE v.validation_status = 'ACTIVE'"
                + " AND (r.active_from IS NULL OR r.active_from <= now())"
                + " AND (r.active_to IS NULL OR r.active_to > now())"
                + " AND (CAST(:board AS text) IS NULL OR r.board_id = :board)")
        .param("board", boardId)
        .query(Integer.class)
        .single();
  }
}
