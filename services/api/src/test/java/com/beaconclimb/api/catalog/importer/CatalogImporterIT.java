package com.beaconclimb.api.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.catalog.importer.ImportRefusedException.Reason;
import com.beaconclimb.api.catalog.importer.TestCatalogs.Spec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** BC-0105 acceptance: happy path, idempotent re-run, malformed rows, gating, digest check. */
class CatalogImporterIT extends ImporterTestSupport {

  private static final String DEMO_SHA =
      "22b86fe2e0d940b82dcd4d326d963d9f5a35e96b8005031b93c4e5eb37589964";
  private static final int DEMO_ROUTES = 300;
  private static final int DEMO_VARIANTS = 609;
  private static final int DEMO_HOLDS = 144;

  @TempDir Path dir;

  @Test
  void importsDemoBoardThenReimportInsertsNothing() {
    ImportResult first = importFile(DEMO_BOARD, false);

    assertThat(first.inserted().routes()).isEqualTo(DEMO_ROUTES);
    assertThat(first.inserted().variants()).isEqualTo(DEMO_VARIANTS);
    assertThat(first.inserted().holds()).isEqualTo(DEMO_HOLDS);
    assertThat(first.inserted().boards()).isEqualTo(1);
    assertThat(first.rejected()).isZero();
    assertThat(first.reportFile()).isNull();
    assertThat(first.exitCode()).isZero();
    assertThat(first.provenanceInserted()).isTrue();
    assertThat(first.summary())
        .startsWith(
            "imported routes=300 variants=609 holds=144 rejected=0 provenance="
                + first.provenanceId());
    assertProvenance(first.provenanceId(), DEMO_SHA, true, "SYNTHETIC");
    assertDemoRowCounts(first.provenanceId());
    int placements = count("SELECT count(*) FROM route_placements");

    ImportResult second = importFile(DEMO_BOARD, false);

    assertThat(second.provenanceId()).isEqualTo(first.provenanceId());
    assertThat(second.provenanceInserted()).isFalse();
    assertThat(second.inserted().routes()).isZero();
    assertThat(second.inserted().variants()).isZero();
    assertThat(second.inserted().holds()).isZero();
    assertThat(second.inserted().boards()).isZero();
    assertThat(second.updated().routes()).isEqualTo(DEMO_ROUTES);
    assertThat(second.updated().variants()).isEqualTo(DEMO_VARIANTS);
    assertThat(second.updated().holds()).isEqualTo(DEMO_HOLDS);
    assertThat(second.summary()).contains("updated routes=300 variants=609 holds=144");
    assertDemoRowCounts(first.provenanceId());
    assertThat(count("SELECT count(*) FROM route_placements")).isEqualTo(placements);
    assertThat(count("SELECT count(*) FROM provenance_sources WHERE content_sha256 = ?", DEMO_SHA))
        .isEqualTo(1);
  }

  @Test
  void malformedRowsAreReportedAndSkippedWhileGoodRowsCommit() throws IOException {
    String suffix = "bad-" + UUID.randomUUID().toString().substring(0, 8);
    String badRole =
        "{\"type\":\"route\",\"provider_route_uuid\":\"syn-mini-0009\",\"board_id\":"
            + "\"synthetic-mini-1\",\"name\":null,\"setter_pseudonym\":null,\"tags\":[],"
            + "\"active_from\":null,\"active_to\":null,\"placements\":[{\"provider_hold_id\":"
            + "\"h1\",\"role\":\"MATCH\"}],\"variants\":[{\"angle_deg\":20,"
            + "\"grade_numeric\":1,\"grade_observation_count\":1}]}";
    String unknownHold =
        badRole
            .replace("syn-mini-0009", "syn-mini-0010")
            .replace("MATCH", "START")
            .replace("\"h1\"", "\"h99\"");
    Path file =
        TestCatalogs.write(
            dir, MINI, Spec.synthetic(suffix).withExtraLines(List.of(badRole, unknownHold)));

    ImportResult result = importFile(file, false);

    assertThat(result.rejected()).isEqualTo(2);
    assertThat(result.exitCode()).isEqualTo(ImportResult.EXIT_REJECTED_ROWS);
    assertThat(result.inserted().routes()).isEqualTo(2);
    assertThat(result.inserted().variants()).isEqualTo(3);
    assertThat(result.inserted().holds()).isEqualTo(4);
    assertThat(result.reportFile()).isNotNull().exists();
    assertThat(result.reportFile().getParent()).isEqualTo(reportDir);
    List<JsonNode> report = readReport(result.reportFile());
    assertThat(report).hasSize(2);
    assertThat(report.get(0).get("line").asInt()).isEqualTo(5);
    assertThat(report.get(0).get("reason").stringValue()).startsWith("SCHEMA_INVALID");
    assertThat(report.get(0).get("record_type").stringValue()).isEqualTo("route");
    assertThat(report.get(0).get("id").stringValue()).isEqualTo("syn-" + suffix + "-0009");
    assertThat(report.get(1).get("line").asInt()).isEqualTo(6);
    assertThat(report.get(1).get("reason").stringValue()).startsWith("UNKNOWN_HOLD h99");
    assertThat(
            count("SELECT count(*) FROM routes WHERE board_id = ?", TestCatalogs.boardId(suffix)))
        .isEqualTo(2);
  }

  @Test
  void nonSyntheticFileNeedsAuditableLicenseAndAcknowledgement() throws IOException {
    String suffix = "gate-" + UUID.randomUUID().toString().substring(0, 8);
    Path file =
        TestCatalogs.write(dir, MINI, Spec.synthetic(suffix).nonSynthetic("LOCAL_RESEARCH_ONLY"));

    assertThatThrownBy(() -> importFile(file, false))
        .isInstanceOf(ImportRefusedException.class)
        .satisfies(e -> assertThat(((ImportRefusedException) e).exitCode()).isEqualTo(3))
        .extracting(e -> ((ImportRefusedException) e).reason())
        .isEqualTo(Reason.LICENSE_GATE);
    assertThat(
            count("SELECT count(*) FROM boards WHERE board_id = ?", TestCatalogs.boardId(suffix)))
        .isZero();

    ImportResult result = importFile(file, true);

    assertThat(result.inserted().routes()).isEqualTo(2);
    assertThat(result.rejected()).isZero();
    assertProvenance(result.provenanceId(), null, false, "LOCAL_RESEARCH_ONLY");
    assertThat(
            jdbc.sql("SELECT synthetic FROM boards WHERE board_id = :id")
                .param("id", TestCatalogs.boardId(suffix))
                .query(Boolean.class)
                .single())
        .isFalse();
  }

  @Test
  void nonSyntheticFileClaimingSyntheticLicenseFailsTheManifestSchema() throws IOException {
    String suffix = "gate2-" + UUID.randomUUID().toString().substring(0, 8);
    Path file = TestCatalogs.write(dir, MINI, Spec.synthetic(suffix).nonSynthetic("SYNTHETIC"));

    assertThatThrownBy(() -> importFile(file, true))
        .isInstanceOf(ImportRefusedException.class)
        .extracting(e -> ((ImportRefusedException) e).reason())
        .isEqualTo(Reason.FILE_INVALID);
  }

  @Test
  void contentSha256MismatchRefusesTheFileAndWritesNothing() throws IOException {
    String suffix = "sha-" + UUID.randomUUID().toString().substring(0, 8);
    String wrong = "f".repeat(64);
    Path file = TestCatalogs.write(dir, MINI, Spec.synthetic(suffix).withContentSha256(wrong));

    assertThatThrownBy(() -> importFile(file, false))
        .isInstanceOf(ImportRefusedException.class)
        .hasMessageContaining("CONTENT_SHA256_MISMATCH")
        .satisfies(e -> assertThat(((ImportRefusedException) e).exitCode()).isEqualTo(4));
    assertThat(count("SELECT count(*) FROM provenance_sources WHERE content_sha256 = ?", wrong))
        .isZero();
    assertThat(
            count("SELECT count(*) FROM boards WHERE board_id = ?", TestCatalogs.boardId(suffix)))
        .isZero();
    assertThat(Files.list(reportDir)).isEmpty();
  }

  @Test
  void nonJsonLineMakesTheDigestUnverifiable() throws IOException {
    String suffix = "json-" + UUID.randomUUID().toString().substring(0, 8);
    Path file = TestCatalogs.write(dir, MINI, Spec.synthetic(suffix));
    Files.writeString(
        file, "{not json\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

    assertThatThrownBy(() -> importFile(file, false))
        .isInstanceOf(ImportRefusedException.class)
        .hasMessageContaining("NOT_JSON line 5")
        .extracting(e -> ((ImportRefusedException) e).reason())
        .isEqualTo(Reason.CONTENT_SHA256_MISMATCH);
  }

  private void assertProvenance(UUID id, String sha, boolean synthetic, String license) {
    JsonNode row =
        jdbc.sql(
                "SELECT content_sha256, synthetic, license_review_status, source, imported_at"
                    + " FROM provenance_sources WHERE provenance_id = :id")
            .param("id", id)
            .query(
                (rs, n) ->
                    CatalogRecords.MAPPER.readTree(
                        CatalogRecords.MAPPER.writeValueAsString(
                            java.util.Map.of(
                                "sha", rs.getString("content_sha256"),
                                "synthetic", rs.getBoolean("synthetic"),
                                "license", rs.getString("license_review_status"),
                                "source", rs.getString("source")))))
            .single();
    if (sha != null) {
      assertThat(row.get("sha").stringValue()).isEqualTo(sha);
    }
    assertThat(row.get("synthetic").booleanValue()).isEqualTo(synthetic);
    assertThat(row.get("license").stringValue()).isEqualTo(license);
  }

  private void assertDemoRowCounts(UUID provenanceId) {
    assertThat(count("SELECT count(*) FROM routes WHERE provenance_id = ?", provenanceId))
        .isEqualTo(DEMO_ROUTES);
    assertThat(
            count(
                "SELECT count(*) FROM route_variants v JOIN routes r ON r.route_id = v.route_id"
                    + " WHERE r.provenance_id = ?",
                provenanceId))
        .isEqualTo(DEMO_VARIANTS);
    assertThat(count("SELECT count(*) FROM holds WHERE board_id = 'synthetic-demo-1'"))
        .isEqualTo(DEMO_HOLDS);
    assertThat(
            count(
                "SELECT count(*) FROM route_placements p JOIN route_variants v"
                    + " ON v.variant_id = p.variant_id JOIN routes r ON r.route_id = v.route_id"
                    + " WHERE r.provenance_id = ?",
                provenanceId))
        .isPositive();
  }
}
