package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The bundled schema accepts the ADR-0003 fixtures branch by branch and rejects a broken record.
 */
class DecisionRecordSchemaTest {

  private static final Path EXAMPLES =
      Path.of("..", "..", "contracts", "decisions", "examples").toAbsolutePath().normalize();
  private final DecisionRecordSchema schema = new DecisionRecordSchema();
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void fixturesValidateAgainstTheirBranches() throws IOException {
    assertThat(schema.validateRecord(read("synthetic-decision.json"))).isEmpty();
    assertThat(schema.validateSnapshot(read("synthetic-snapshot.json"))).isEmpty();
    assertThat(schema.validateRecord(read("synthetic-snapshot.json"))).isNotEmpty();
    assertThat(schema.validateSnapshot(read("synthetic-decision.json"))).isNotEmpty();
  }

  @Test
  void aViolationIsAnIllegalStateNamingTheMember() throws IOException {
    ObjectNode record = (ObjectNode) read("synthetic-decision.json");
    record.put("rng_seed", "not-hex");
    record.put("surprise", 1);

    assertThatThrownBy(() -> schema.requireValidRecord(record))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rng_seed")
        .hasMessageContaining("surprise");
    schema.requireValidSnapshot(read("synthetic-snapshot.json"));
  }

  private JsonNode read(String name) throws IOException {
    return json.readTree(Files.readString(EXAMPLES.resolve(name), StandardCharsets.UTF_8));
  }
}
