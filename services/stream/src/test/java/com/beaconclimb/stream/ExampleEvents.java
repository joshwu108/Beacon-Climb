package com.beaconclimb.stream;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The committed contract examples (contracts/events/examples), copied into test resources. */
final class ExampleEvents {

  static final JsonMapper MAPPER = JsonMapper.builder().build();
  static final String USER_ID = "018f0000-0000-7000-8000-000000000001";

  private ExampleEvents() {}

  /** The {@code event} member of an example file, as a mutable tree. */
  static ObjectNode example(String name) {
    String resource = "contracts/events/examples/" + name + ".json";
    try (InputStream in = ExampleEvents.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(resource + " missing; run processTestResources");
      }
      JsonNode wrapper = MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      return (ObjectNode) wrapper.get("event");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] bytes(JsonNode event) {
    return MAPPER.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
  }
}
