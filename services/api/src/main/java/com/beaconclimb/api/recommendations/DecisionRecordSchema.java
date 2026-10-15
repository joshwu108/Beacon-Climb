package com.beaconclimb.api.recommendations;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The decision contract contracts/decisions/decision-record.v1.schema.json, copied into the jar by
 * the build (services/api/build.gradle.kts). The record and the snapshot are validated against
 * their own {@code $defs} branch so a violation names the real member. A violation at request time
 * is a programming error, never client input, so {@link #requireValidRecord} and {@link
 * #requireValidSnapshot} throw {@link IllegalStateException} (a 500) and nothing is persisted.
 */
@Component
public class DecisionRecordSchema {

  public static final String RESOURCE = "contracts/decisions/decision-record.v1.schema.json";
  static final String SCHEMA_ID =
      "https://beacon-climb.local/contracts/decisions/decision-record.v1.schema.json";
  private static final int MAX_ERRORS_IN_MESSAGE = 5;

  private final Schema fullRecord;
  private final Schema candidateSnapshot;

  public DecisionRecordSchema() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    String document = readResource();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemas(Map.of(SCHEMA_ID, document)).schemaRegistryConfig(config));
    this.fullRecord = registry.getSchema(SchemaLocation.of(SCHEMA_ID + "#/$defs/FullRecord"));
    this.candidateSnapshot =
        registry.getSchema(SchemaLocation.of(SCHEMA_ID + "#/$defs/CandidateSnapshot"));
  }

  public List<Error> validateRecord(JsonNode record) {
    return fullRecord.validate(record);
  }

  public List<Error> validateSnapshot(JsonNode snapshot) {
    return candidateSnapshot.validate(snapshot);
  }

  public void requireValidRecord(JsonNode record) {
    require("decision record", validateRecord(record));
  }

  public void requireValidSnapshot(JsonNode snapshot) {
    require("candidate snapshot", validateSnapshot(snapshot));
  }

  private static void require(String what, List<Error> errors) {
    if (errors.isEmpty()) {
      return;
    }
    String joined =
        errors.stream()
            .limit(MAX_ERRORS_IN_MESSAGE)
            .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
            .collect(Collectors.joining("; "));
    throw new IllegalStateException(what + " violates " + RESOURCE + ": " + joined);
  }

  private static String readResource() {
    try (InputStream in =
        DecisionRecordSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(
            "classpath resource " + RESOURCE + " missing; run the Gradle build (processResources)");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + RESOURCE, e);
    }
  }
}
