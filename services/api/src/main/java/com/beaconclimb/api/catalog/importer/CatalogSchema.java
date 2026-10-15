package com.beaconclimb.api.catalog.importer;

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
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Catalog v1 record validation against contracts/catalog/catalog-v1.schema.json, which the build
 * copies into the jar (see services/api/build.gradle.kts). Each record type is validated against
 * its own {@code $defs} branch, chosen by the {@code type} discriminator, so errors name the real
 * violation instead of a {@code oneOf} mismatch.
 */
@Component
public class CatalogSchema {

  public static final String RESOURCE = "contracts/catalog/catalog-v1.schema.json";
  static final String SCHEMA_ID =
      "https://beacon-climb.local/contracts/catalog/catalog-v1.schema.json";
  private static final List<String> TYPES =
      List.of(CatalogRecords.TYPE_MANIFEST, CatalogRecords.TYPE_BOARD, CatalogRecords.TYPE_ROUTE);
  private static final int MAX_ERRORS_IN_REASON = 5;

  private final Map<String, Schema> schemasByType;

  public CatalogSchema() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    String document = readResource();
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemas(Map.of(SCHEMA_ID, document)).schemaRegistryConfig(config));
    this.schemasByType =
        TYPES.stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    type -> type,
                    type -> registry.getSchema(SchemaLocation.of(SCHEMA_ID + "#/$defs/" + type))));
  }

  /** The {@code type} discriminator when the line is an object with a known record type. */
  public Optional<String> recordType(JsonNode node) {
    if (node == null || !node.isObject()) {
      return Optional.empty();
    }
    JsonNode type = node.get("type");
    if (type == null || !type.isString() || !schemasByType.containsKey(type.stringValue())) {
      return Optional.empty();
    }
    return Optional.of(type.stringValue());
  }

  /** Empty when the record conforms; otherwise one short reason naming the first violations. */
  public Optional<String> validate(String type, JsonNode node) {
    List<Error> errors = schemasByType.get(type).validate(node);
    if (errors.isEmpty()) {
      return Optional.empty();
    }
    String joined =
        errors.stream()
            .limit(MAX_ERRORS_IN_REASON)
            .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
            .collect(java.util.stream.Collectors.joining("; "));
    return Optional.of("SCHEMA_INVALID " + joined);
  }

  private static String readResource() {
    try (InputStream in = CatalogSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
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
