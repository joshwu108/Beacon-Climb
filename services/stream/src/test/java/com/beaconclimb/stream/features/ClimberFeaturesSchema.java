package com.beaconclimb.stream.features;

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
 * wired exactly like the shared EventSchemas (BC-0302), so the stream tests judge every emitted
 * record the way the api materializer will. Recreated for BC-0304 (the BC-0302 change removed it
 * when the shared validator took over the job side). The schema resource comes from the :events
 * jar.
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
        throw new IllegalStateException(RESOURCE + " missing from the :events jar");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
