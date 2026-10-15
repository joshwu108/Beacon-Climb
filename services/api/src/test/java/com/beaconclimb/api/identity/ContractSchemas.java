package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates response bodies against the component schemas of the frozen contract
 * contracts/openapi.yaml (OpenAPI 3.1, JSON Schema 2020-12 vocabulary) with networknt
 * json-schema-validator. Format assertions are on, so {@code uuid} and {@code date-time} are
 * checked, and {@code additionalProperties: false} proves no extra members leak.
 */
public final class ContractSchemas {

  static final Path CONTRACT =
      Path.of("..", "..", "contracts", "openapi.yaml").toAbsolutePath().normalize();
  private static final String DOCUMENT_IRI = "https://beacon-climb.local/contracts/openapi.json";
  private static final SchemaRegistry REGISTRY = buildRegistry();

  private ContractSchemas() {}

  public static List<com.networknt.schema.Error> validate(String schemaName, String jsonBody) {
    Schema schema =
        REGISTRY.getSchema(SchemaLocation.of(DOCUMENT_IRI + "#/components/schemas/" + schemaName));
    return schema.validate(jsonBody, InputFormat.JSON);
  }

  public static void assertConforms(String schemaName, String jsonBody) {
    assertThat(jsonBody).as("body for %s", schemaName).isNotBlank();
    assertThat(validate(schemaName, jsonBody))
        .as("%s must conform to components.schemas.%s", jsonBody, schemaName)
        .isEmpty();
  }

  private static SchemaRegistry buildRegistry() {
    String document = contractAsJson();
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    return SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12,
        builder -> builder.schemas(Map.of(DOCUMENT_IRI, document)).schemaRegistryConfig(config));
  }

  /** The contract uses hundreds of YAML aliases, far above SnakeYAML's default limit of 50. */
  private static String contractAsJson() {
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(Integer.MAX_VALUE);
    try (Reader reader = Files.newBufferedReader(CONTRACT, StandardCharsets.UTF_8)) {
      Object document = new Yaml(options).load(reader);
      return JsonMapper.builder().build().writeValueAsString(document);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + CONTRACT, e);
    }
  }
}
