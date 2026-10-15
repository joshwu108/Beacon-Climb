package com.beaconclimb.api.recommendations;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The decision contract read from the repository (contracts/decisions), independent of the copy
 * bundled into the jar, validated at the document root so the {@code oneOf} over snapshot, record
 * and erased stub is exercised.
 */
final class DecisionContractSchema {

  static final Path SCHEMA_FILE =
      Path.of("..", "..", "contracts", "decisions", "decision-record.v1.schema.json")
          .toAbsolutePath()
          .normalize();
  private static final String SCHEMA_ID =
      "https://beacon-climb.local/contracts/decisions/decision-record.v1.schema.json";
  private static final Schema ROOT = load("");
  private static final Schema SNAPSHOT = load("#/$defs/CandidateSnapshot");
  private static final Schema RECORD = load("#/$defs/FullRecord");

  private DecisionContractSchema() {}

  static List<Error> validateRoot(String json) {
    return ROOT.validate(json, InputFormat.JSON);
  }

  static List<Error> validateSnapshot(String json) {
    return SNAPSHOT.validate(json, InputFormat.JSON);
  }

  static List<Error> validateRecord(String json) {
    return RECORD.validate(json, InputFormat.JSON);
  }

  private static Schema load(String fragment) {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemas(Map.of(SCHEMA_ID, read())).schemaRegistryConfig(config));
    return registry.getSchema(SchemaLocation.of(SCHEMA_ID + fragment));
  }

  private static String read() {
    try {
      return Files.readString(SCHEMA_FILE, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + SCHEMA_FILE, e);
    }
  }
}
