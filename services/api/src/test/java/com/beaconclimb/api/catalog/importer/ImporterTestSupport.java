package com.beaconclimb.api.catalog.importer;

import com.beaconclimb.api.AbstractPostgresIT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;

/**
 * Importer harness: a context without a web server (the same shape the {@code import} profile runs
 * with) against the shared Postgres, the importer service called directly, and a temp report
 * directory. The runner itself is not activated so no import happens at context start.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class ImporterTestSupport extends AbstractPostgresIT {

  protected static final Path REPO_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
  protected static final Path DEMO_BOARD =
      REPO_ROOT.resolve("ml/datasets/synthetic/fixtures/demo-board-v1.jsonl");
  protected static final Path MINI =
      REPO_ROOT.resolve("contracts/catalog/examples/synthetic-mini.jsonl");
  protected static final Path INVALID_DIR = REPO_ROOT.resolve("contracts/catalog/examples/invalid");

  @Autowired protected CatalogImporter importer;
  @Autowired protected JdbcClient jdbc;
  @TempDir protected Path reportDir;

  protected ImportResult importFile(Path file, boolean ack) {
    return importer.importFile(file, ack, reportDir);
  }

  /**
   * Removes a board with all its routes, variants, placements and holds, and the provenance row
   * {@code sha} once nothing references it, so a test can observe a cold import no matter which
   * class (the BC-0106 endpoint tests also load the demo board) ran first in this JVM. The audit
   * rows are deleted as the schema owner: since V7 (BC-0123 F6) the app role cannot delete from
   * {@code attempt_revisions}, exactly as the future erasure job will need its own role.
   */
  protected void deleteBoard(String boardId, String sha) {
    // BC-0109: attempts (and their audit and outbox rows) reference the board's variants.
    String boardAttempts =
        "SELECT a.attempt_id FROM attempts a JOIN route_variants v ON v.variant_id = a.variant_id"
            + " JOIN routes r ON r.route_id = v.route_id WHERE r.board_id = :b";
    jdbc.sql("DELETE FROM attempt_event_outbox WHERE aggregate_id IN (" + boardAttempts + ")")
        .param("b", boardId)
        .update();
    schemaOwner()
        .sql("DELETE FROM attempt_revisions WHERE attempt_id IN (" + boardAttempts + ")")
        .param("b", boardId)
        .update();
    jdbc.sql("DELETE FROM attempts WHERE attempt_id IN (" + boardAttempts + ")")
        .param("b", boardId)
        .update();
    jdbc.sql(
            "DELETE FROM route_placements WHERE variant_id IN (SELECT v.variant_id FROM"
                + " route_variants v JOIN routes r ON r.route_id = v.route_id WHERE r.board_id = :b)")
        .param("b", boardId)
        .update();
    jdbc.sql(
            "DELETE FROM route_variants WHERE route_id IN"
                + " (SELECT route_id FROM routes WHERE board_id = :b)")
        .param("b", boardId)
        .update();
    jdbc.sql("DELETE FROM routes WHERE board_id = :b").param("b", boardId).update();
    jdbc.sql("DELETE FROM holds WHERE board_id = :b").param("b", boardId).update();
    jdbc.sql("DELETE FROM boards WHERE board_id = :b").param("b", boardId).update();
    jdbc.sql(
            "DELETE FROM provenance_sources p WHERE p.content_sha256 = :sha AND NOT EXISTS"
                + " (SELECT 1 FROM boards WHERE provenance_id = p.provenance_id) AND NOT EXISTS"
                + " (SELECT 1 FROM routes WHERE provenance_id = p.provenance_id)")
        .param("sha", sha)
        .update();
  }

  private static JdbcClient schemaOwner() {
    return JdbcClient.create(
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "beacon_migrate", MIGRATE_PASSWORD));
  }

  protected int count(String sql, Object... params) {
    JdbcClient.StatementSpec spec = jdbc.sql(sql);
    for (Object param : params) {
      spec = spec.param(param);
    }
    return spec.query(Integer.class).single();
  }

  protected static List<JsonNode> readReport(Path report) throws IOException {
    List<JsonNode> entries = new ArrayList<>();
    for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
      entries.add(CatalogRecords.MAPPER.readTree(line));
    }
    return entries;
  }
}
