package com.beaconclimb.stream;

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
import tools.jackson.databind.JsonNode;

/**
 * contracts/events/climber-features.v1.schema.json with format assertions on (uuid, date-time),
 * wired exactly like the api's AttemptEventSchema so both sides judge conformance the same way.
 */
final class ClimberFeaturesSchema {

  static final String RESOURCE = "contracts/events/climber-features.v1.schema.json";
  static final String SCHEMA_ID =
      "https://beacon-climb.local/contracts/events/climber-features.v1.schema.json";

  private final Schema schema;

  ClimberFeaturesSchema() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemas(Map.of(SCHEMA_ID, read())).schemaRegistryConfig(config));
    this.schema = registry.getSchema(SchemaLocation.of(SCHEMA_ID));
  }

  /** Empty when the record conforms. */
  List<Error> validate(JsonNode record) {
    return schema.validate(record);
  }

  private static String read() {
    try (InputStream in =
        ClimberFeaturesSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " missing; run processTestResources");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
