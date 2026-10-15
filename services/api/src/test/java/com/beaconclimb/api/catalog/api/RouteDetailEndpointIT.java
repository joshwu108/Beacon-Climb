package com.beaconclimb.api.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** BC-0106 {@code getRoute}: happy path, placements, tags, ETag/304 and every 404 shape. */
class RouteDetailEndpointIT extends CatalogTestSupport {

  /** syn-route-0001 in the demo fixture: 24 placements, no tags, variants at 40 and 50. */
  private static final String ROUTE_0001_40 = "/v1/routes/syn-route-0001:40";

  private static final int ROUTE_0001_PLACEMENTS = 24;

  @Test
  void requiresBearerToken() {
    assertError(get(ROUTE_0001_40, null), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void returnsTheVariantWithOrderedPlacementsAndMissingTags() {
    ResponseEntity<String> response = get(ROUTE_0001_40, token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("RouteVariant", response.getBody());
    JsonNode route = body(response);
    assertThat(route.get("route_variant_id").asString()).isEqualTo("syn-route-0001:40");
    assertThat(route.get("board_id").asString()).isEqualTo(CatalogSeed.BOARD_ID);
    assertThat(route.get("angle_deg").asInt()).isEqualTo(40);
    assertThat(route.get("name").asString()).isEqualTo("Brittle Orbit");
    assertThat(route.get("grade_numeric").asDouble()).isEqualTo(20.6);
    assertThat(route.get("grade_label").isNull()).isTrue();
    assertThat(route.get("placements")).hasSize(ROUTE_0001_PLACEMENTS);
    assertThat(route.get("placements").get(0).get("role").asString()).isEqualTo("START");
    assertThat(route.get("placements").get(0).get("hold_id").asString()).isEqualTo("h008");
    List<String> holdIds =
        route.get("placements").valueStream().map(p -> p.get("hold_id").asString()).toList();
    assertThat(holdIds).isEqualTo(placementOrderInTables("syn-route-0001", 40));
    assertThat(route.get("placements").valueStream().map(p -> p.get("role").asString()))
        .contains("START", "FINISH");
    assertThat(route.get("placements").get(0).get("x").isNumber()).isTrue();
    assertThat(route.get("tags")).isEmpty();
    assertThat(route.get("missing_tags").asBoolean()).isTrue();
    assertThat(
            count(
                "SELECT count(*) FROM route_placements p JOIN route_variants v"
                    + " ON v.variant_id = p.variant_id JOIN routes r ON r.route_id = v.route_id"
                    + " WHERE r.provider_route_uuid = 'syn-route-0001' AND v.angle_deg = 40"))
        .isEqualTo(ROUTE_0001_PLACEMENTS);
  }

  @Test
  void taggedRouteReportsItsTags() {
    ResponseEntity<String> response = get("/v1/routes/syn-route-0002:40", token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("RouteVariant", response.getBody());
    JsonNode route = body(response);
    assertThat(route.get("tags").valueStream().map(JsonNode::asString).toList())
        .containsExactly("balancy", "slopers");
    assertThat(route.get("missing_tags").asBoolean()).isFalse();
  }

  @Test
  void weakEtagAndNotModified() {
    ResponseEntity<String> first = get(ROUTE_0001_40, token);
    String etag = first.getHeaders().getETag();
    assertThat(etag).startsWith("W/\"").hasSize(3 + 64 + 1);

    ResponseEntity<String> again = get(ROUTE_0001_40, token);
    assertThat(again.getHeaders().getETag()).isEqualTo(etag);

    ResponseEntity<String> conditional =
        getWithHeaders(ROUTE_0001_40, token, Map.of(HttpHeaders.IF_NONE_MATCH, etag));
    assertThat(conditional.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(conditional.getBody()).isNull();
    assertThat(conditional.getHeaders().getETag()).isEqualTo(etag);

    ResponseEntity<String> stale =
        getWithHeaders(ROUTE_0001_40, token, Map.of(HttpHeaders.IF_NONE_MATCH, "W/\"stale\""));
    assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void unknownAndMalformedIdsAre404() {
    for (String id :
        List.of(
            "syn-route-0001:35",
            "nonsense",
            "syn-route-0001:040",
            "syn-route-0001:+40",
            "syn-route-0001:-0",
            "no-such-route:40",
            CatalogSeed.FIXTURE_RETIRED_ID,
            "test-retired:40",
            "test-invalid:40",
            "test-expired:40",
            "test-future:40")) {
      assertError(get("/v1/routes/" + id, token), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
    }
  }

  private int count(String sql) {
    return db.sql(sql).query(Integer.class).single();
  }

  /** Setter order as stored: placement_order, then hold id for ties or missing orders. */
  private List<String> placementOrderInTables(String routeUuid, int angle) {
    return db.sql(
            "SELECT h.provider_hold_id FROM route_placements p JOIN holds h ON h.hold_id ="
                + " p.hold_id JOIN route_variants v ON v.variant_id = p.variant_id JOIN routes r ON"
                + " r.route_id = v.route_id WHERE r.provider_route_uuid = :u AND v.angle_deg = :a"
                + " ORDER BY p.placement_order ASC NULLS LAST, h.provider_hold_id, p.role")
        .param("u", routeUuid)
        .param("a", angle)
        .query(String.class)
        .list();
  }
}
