package com.beaconclimb.api.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0106 {@code listBoards}: auth, the demo board with every hold and geometry, contract shape.
 */
class BoardsEndpointIT extends CatalogTestSupport {

  private static final int DEMO_HOLDS = 144;

  @Test
  void requiresBearerToken() {
    assertError(get("/v1/boards", null), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void returnsTheSyntheticBoardWithHoldsGeometryAndAngles() {
    ResponseEntity<String> response = get("/v1/boards", token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("BoardList", response.getBody());
    JsonNode items = body(response).get("items");
    List<String> ids = items.valueStream().map(b -> b.get("board_id").asString()).toList();
    assertThat(ids).contains(CatalogSeed.BOARD_ID).isSorted();
    JsonNode board =
        items
            .valueStream()
            .filter(b -> b.get("board_id").asString().equals(CatalogSeed.BOARD_ID))
            .findFirst()
            .orElseThrow();
    assertThat(board.get("synthetic").asBoolean()).isTrue();
    assertThat(board.get("provider").asString()).isEqualTo("synthetic-demo");
    assertThat(board.get("layout_id").isString()).isTrue();
    assertThat(board.get("holds")).hasSize(DEMO_HOLDS);
    JsonNode firstHold = board.get("holds").get(0);
    assertThat(firstHold.get("hold_id").asString()).isEqualTo("h001");
    assertThat(firstHold.get("x").isNumber()).isTrue();
    assertThat(firstHold.get("x").asDouble()).isEqualTo(0.042);
    assertThat(board.get("geometry").get("units").asString()).isEqualTo("NORMALIZED");
    assertThat(board.get("geometry").get("y_axis").asString()).isEqualTo("UP");
    assertThat(board.get("geometry").get("width").isNumber()).isTrue();
    assertThat(board.get("available_angles").valueStream().map(JsonNode::asInt).toList())
        .containsExactly(20, 30, 40, 45, 50);
  }
}
