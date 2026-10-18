package com.beaconclimb.events;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The three event contracts (contracts/events/*.schema.json, copied into this jar by the build),
 * keyed by topic name, compiled once with format assertions on so {@code uuid} and {@code
 * date-time} members are checked. Both the api and the Flink job judge conformance through this
 * class, never through a hand-written allowlist.
 */
public final class EventSchemas {

  public static final String ATTEMPTS = "attempts.v1";
  public static final String FEATURES = "climber-features.v1";
  public static final String DLQ = "stream-dlq.v1";

  static final String RESOURCE_DIR = "contracts/events/";
  static final String ID_PREFIX = "https://beacon-climb.local/contracts/events/";

  private final Map<String, Schema> schemas;

  private EventSchemas(Map<String, Schema> schemas) {
    this.schemas = Map.copyOf(schemas);
  }

  /** Compiles the bundled schemas; a missing resource is a build error, not a runtime state. */
  public static EventSchemas load() {
    SchemaRegistryConfig config =
        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
    Map<String, String> sources = new LinkedHashMap<>();
    for (String topic : List.of(ATTEMPTS, FEATURES, DLQ)) {
      sources.put(ID_PREFIX + topic + ".schema.json", readResource(topic));
    }
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemas(sources).schemaRegistryConfig(config));
    Map<String, Schema> compiled = new LinkedHashMap<>();
    for (String topic : List.of(ATTEMPTS, FEATURES, DLQ)) {
      compiled.put(
          topic, registry.getSchema(SchemaLocation.of(ID_PREFIX + topic + ".schema.json")));
    }
    return new EventSchemas(compiled);
  }

  public Set<String> topics() {
    return schemas.keySet();
  }

  public boolean knows(String topic) {
    return schemas.containsKey(topic);
  }

  /** The schema errors of {@code document} against the topic's contract; empty when it conforms. */
  public List<Error> errors(String topic, JsonNode document) {
    Schema schema = schemas.get(topic);
    if (schema == null) {
      throw new IllegalArgumentException("no event schema for topic '" + topic + "'");
    }
    return schema.validate(document);
  }

  private static String readResource(String topic) {
    String resource = RESOURCE_DIR + topic + ".schema.json";
    try (InputStream in = EventSchemas.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(
            "classpath resource " + resource + " missing; run the Gradle build (processResources)");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + resource, e);
    }
  }
}
