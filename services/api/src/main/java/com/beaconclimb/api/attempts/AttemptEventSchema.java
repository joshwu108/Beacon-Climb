package com.beaconclimb.api.attempts;

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
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The {@code attempts.v1} event contract (contracts/events/attempts.v1.schema.json), copied into
 * the jar by the build (services/api/build.gradle.kts). Format assertions are on so {@code uuid}
 * and {@code date-time} members are checked, and the closed {@code attributes} allowlist (C-01,
 * TM-11) is enforced before any payload reaches the outbox.
 */
@Component
public class AttemptEventSchema {

  public static final String RESOURCE = "contracts/events/attempts.v1.schema.json";
  static final String SCHEMA_ID =
      "https://beacon-climb.local/contracts/events/attempts.v1.schema.json";

  private final Schema schema;

  public AttemptEventSchema() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder ->
                builder.schemas(Map.of(SCHEMA_ID, readResource())).schemaRegistryConfig(config));
    this.schema = registry.getSchema(SchemaLocation.of(SCHEMA_ID));
  }

  /** Empty when the event conforms. */
  public List<Error> validate(JsonNode event) {
    return schema.validate(event);
  }

  private static String readResource() {
    try (InputStream in = AttemptEventSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
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
