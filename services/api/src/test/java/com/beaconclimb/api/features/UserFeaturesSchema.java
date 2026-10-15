package com.beaconclimb.api.features;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code $defs/userFeatures} subschema of contracts/decisions/decision-record.v1.schema.json:
 * every aggregates_json must validate against it (it is the frozen user_features minus the
 * policy-derived target grade).
 */
final class UserFeaturesSchema {

  static final Path SCHEMA_FILE =
      Path.of("..", "..", "contracts", "decisions", "decision-record.v1.schema.json")
          .toAbsolutePath()
          .normalize();
  static final String SCHEMA_ID =
      "https://beacon-climb.local/contracts/decisions/decision-record.v1.schema.json";
  static final String TARGET_GRADE_KEY = "target_grade_numeric";

  private static final Schema SCHEMA = load();

  private UserFeaturesSchema() {}

  static List<Error> validate(String json) {
    return SCHEMA.validate(json, InputFormat.JSON);
  }

  /** The property names of userFeatures without the key the ranker adds at request time. */
  static List<String> aggregateKeys() {
    JsonNode properties =
        JsonMapper.builder()
            .build()
            .readTree(read())
            .get("$defs")
            .get("userFeatures")
            .get("properties");
    List<String> keys = new ArrayList<>();
    properties.propertyNames().forEach(keys::add);
    keys.remove(TARGET_GRADE_KEY);
    return keys;
  }

  private static Schema load() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemas(Map.of(SCHEMA_ID, read())).schemaRegistryConfig(config));
    return registry.getSchema(SchemaLocation.of(SCHEMA_ID + "#/$defs/userFeatures"));
  }

  private static String read() {
    try {
      return Files.readString(SCHEMA_FILE, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + SCHEMA_FILE, e);
    }
  }
}
