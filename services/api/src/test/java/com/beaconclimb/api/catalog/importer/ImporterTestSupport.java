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
