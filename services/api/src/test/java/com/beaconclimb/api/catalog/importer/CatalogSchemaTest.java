package com.beaconclimb.api.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The contract schema is packaged into the classpath by the build and validates per record type.
 */
class CatalogSchemaTest {

  private static final Path CONTRACT =
      Path.of("..", "..", "contracts", "catalog", "catalog-v1.schema.json")
          .toAbsolutePath()
          .normalize();
  private static final Path MINI =
      Path.of("..", "..", "contracts", "catalog", "examples", "synthetic-mini.jsonl")
          .toAbsolutePath()
          .normalize();

  private final CatalogSchema schema = new CatalogSchema();

  @Test
  void schemaResourceOnClasspathIsTheContractFile() throws IOException {
    byte[] resource =
        CatalogSchemaTest.class
            .getClassLoader()
            .getResourceAsStream(CatalogSchema.RESOURCE)
            .readAllBytes();

    assertThat(resource).isEqualTo(Files.readAllBytes(CONTRACT));
  }

  @Test
  void everyLineOfTheMiniExampleConformsToItsRecordType() throws IOException {
    List<String> lines = Files.readAllLines(MINI, StandardCharsets.UTF_8);
    List<String> types = List.of("manifest", "board", "route", "route");

    for (int i = 0; i < lines.size(); i++) {
      JsonNode node = CatalogRecords.MAPPER.readTree(lines.get(i));
      assertThat(schema.recordType(node)).hasValue(types.get(i));
      assertThat(schema.validate(types.get(i), node)).as("line %d", i + 1).isEmpty();
    }
  }

  @Test
  void manifestBranchEnforcesTheSyntheticLicenseRule() {
    JsonNode manifest =
        CatalogRecords.MAPPER.readTree(
            "{\"type\":\"manifest\",\"schema_version\":1,\"source\":\"s\",\"version\":\"v\","
                + "\"content_sha256\":\""
                + "0".repeat(64)
                + "\",\"extracted_at\":"
                + "\"2026-10-15T00:00:00Z\",\"synthetic\":true,\"license_review_status\":"
                + "\"LOCAL_RESEARCH_ONLY\",\"record_counts\":{\"boards\":1,\"routes\":0}}");

    assertThat(schema.validate("manifest", manifest))
        .hasValueSatisfying(
            reason ->
                assertThat(reason).startsWith("SCHEMA_INVALID").contains("license_review_status"));
    assertThat(schema.recordType(CatalogRecords.MAPPER.readTree("{\"type\":\"hold\"}"))).isEmpty();
    assertThat(schema.recordType(CatalogRecords.MAPPER.readTree("[1]"))).isEmpty();
  }
}
