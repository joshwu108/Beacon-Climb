package com.beaconclimb.api.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.ContractSchemas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The shipped catalog fixtures validate against the same schemas the live bodies are held to. */
class CatalogContractIT extends CatalogTestSupport {

  private static final Path EXAMPLES =
      Path.of("..", "..", "contracts", "examples").toAbsolutePath().normalize();

  @Test
  void shippedCatalogFixturesConformToTheirSchemas() throws IOException {
    Map<String, String> fixtures =
        Map.of(
            "listBoards/response.json", "BoardList",
            "listRoutes/response.json", "RouteList",
            "listRoutes/response-422.json", "ErrorEnvelope",
            "getRoute/response.json", "RouteVariant");
    for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
      JsonNode body =
          mapper
              .readTree(
                  Files.readString(EXAMPLES.resolve(fixture.getKey()), StandardCharsets.UTF_8))
              .get("body");
      ContractSchemas.assertConforms(fixture.getValue(), mapper.writeValueAsString(body));
    }
  }

  @Test
  void validatorRejectsAStringGradeAndAnExtraMember() {
    String stringGrade =
        "{\"items\":[{\"route_variant_id\":\"a:40\",\"board_id\":\"b\",\"angle_deg\":40,"
            + "\"name\":\"n\",\"grade_numeric\":\"17.5\",\"grade_label\":null,"
            + "\"grade_observation_count\":0}],\"next_cursor\":null}";
    assertThat(ContractSchemas.validate("RouteList", stringGrade)).isNotEmpty();
    String extra = "{\"items\":[],\"next_cursor\":null,\"total\":0}";
    assertThat(ContractSchemas.validate("RouteList", extra))
        .anySatisfy(error -> assertThat(error.getKeyword()).isEqualTo("additionalProperties"));
  }
}
