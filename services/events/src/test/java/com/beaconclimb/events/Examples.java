package com.beaconclimb.events;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The committed contract examples (contracts/events/examples), read from the repository. */
final class Examples {

  static final JsonMapper MAPPER = JsonMapper.builder().build();
  static final Path DIR =
      Path.of("..", "..", "contracts", "events", "examples").toAbsolutePath().normalize();

  /** One fixture file: the topic it targets and the event document. */
  record Example(String file, String topic, ObjectNode event) {}

  private Examples() {}

  static Example example(String name) {
    Path file = DIR.resolve(name.endsWith(".json") ? name : name + ".json");
    try {
      JsonNode wrapper = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
      return new Example(
          file.getFileName().toString(),
          wrapper.get("schema").asString(),
          (ObjectNode) wrapper.get("event"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<String> fileNames() {
    try (Stream<Path> files = Files.list(DIR)) {
      return files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".json"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] bytes(JsonNode event) {
    return MAPPER.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
  }

  static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
