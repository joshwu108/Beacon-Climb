package com.beaconclimb.api.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogQueryRepository;
import com.beaconclimb.api.catalog.CatalogQueryRepository.VariantRow;
import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.catalog.RouteCursor;
import com.beaconclimb.api.catalog.RouteQuery;
import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** BC-0106 {@code listRoutes}: auth, defaults, a complete cursor walk, filters and the 422s. */
class RoutesListEndpointIT extends CatalogTestSupport {

  private static final String BOARD = "?board_id=" + CatalogSeed.BOARD_ID;

  @Autowired CatalogQueryRepository repository;

  @Test
  void requiresBearerToken() {
    assertError(get("/v1/routes", null), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void defaultPageHasTwentyItemsAndACursor() {
    ResponseEntity<String> response = get("/v1/routes" + BOARD, token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("RouteList", response.getBody());
    JsonNode page = body(response);
    assertThat(page.get("items")).hasSize(RouteQuery.DEFAULT_LIMIT);
    assertThat(page.get("next_cursor").isString()).isTrue();
    JsonNode first = page.get("items").get(0);
    assertThat(first.get("board_id").asString()).isEqualTo(CatalogSeed.BOARD_ID);
    assertThat(first.get("grade_numeric").isNumber()).isTrue();
    assertThat(first.get("grade_label").isNull()).isTrue();
    assertThat(first.get("name").isString()).isTrue();
  }

  @Test
  void cursorWalkCoversEveryVisibleVariantOnceInGradeOrder() {
    List<JsonNode> all = walk("/v1/routes" + BOARD + "&limit=100");

    int expected = CatalogSeed.visibleCount(db, CatalogSeed.BOARD_ID);
    assertThat(all).hasSize(expected);
    Set<String> ids = new HashSet<>();
    all.forEach(item -> ids.add(item.get("route_variant_id").asString()));
    assertThat(ids).hasSize(expected);
    assertThat(ids).doesNotContainAnyElementsOf(CatalogSeed.hiddenPublicIds(db));
    Double previous = null;
    boolean seenNull = false;
    for (JsonNode item : all) {
      JsonNode grade = item.get("grade_numeric");
      if (grade.isNull()) {
        seenNull = true;
        continue;
      }
      assertThat(seenNull).as("graded after ungraded").isFalse();
      if (previous != null) {
        assertThat(grade.asDouble()).isGreaterThanOrEqualTo(previous);
      }
      previous = grade.asDouble();
    }
    assertThat(seenNull).as("the demo board has ungraded variants").isTrue();
  }

  @Test
  void cursorWalkAcrossAllBoardsMatchesTheTables() {
    assertThat(walk("/v1/routes?limit=100")).hasSize(CatalogSeed.visibleCount(db, null));
  }

  @Test
  void repositoryPagesAreStrictlyOrderedByGradeThenVariantId() {
    List<VariantRow> rows = new ArrayList<>();
    RouteQuery query = new RouteQuery(CatalogSeed.BOARD_ID, null, null, null, null, 7);
    while (true) {
      List<VariantRow> page = repository.listRoutes(query, Instant.now());
      boolean more = page.size() > query.limit();
      List<VariantRow> kept = more ? page.subList(0, query.limit()) : page;
      rows.addAll(kept);
      if (!more) {
        break;
      }
      VariantRow last = kept.get(kept.size() - 1);
      query = query.withCursor(new RouteCursor(last.gradeNumeric(), last.variantId()));
    }
    assertThat(rows).hasSize(CatalogSeed.visibleCount(db, CatalogSeed.BOARD_ID));
    for (int i = 1; i < rows.size(); i++) {
      assertThat(compare(rows.get(i - 1), rows.get(i))).as("row %d", i).isNegative();
    }
  }

  @Test
  void filtersAreHonoured() {
    List<JsonNode> angle = walk("/v1/routes" + BOARD + "&angle_deg=40&limit=100");
    assertThat(angle).isNotEmpty().allMatch(item -> item.get("angle_deg").asInt() == 40);

    List<JsonNode> range = walk("/v1/routes" + BOARD + "&grade_min=18&grade_max=20.5&limit=100");
    assertThat(range).isNotEmpty();
    for (JsonNode item : range) {
      assertThat(item.get("grade_numeric").asDouble()).isBetween(18.0, 20.5);
    }

    assertThat(walk("/v1/routes" + BOARD + "&angle_deg=35&limit=100")).isEmpty();
  }

  @Test
  void unknownBoardIs422UnknownBoard() {
    ErrorEnvelope error =
        assertError(
            get("/v1/routes?board_id=no-such-board", token),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.UNKNOWN_BOARD);
    assertThat(error.fieldErrors()).extracting("field").containsExactly("board_id");
  }

  @Test
  void limitOutOfRangeIs422ValidationFailedWithFieldErrors() {
    ErrorEnvelope high =
        assertError(
            get("/v1/routes" + BOARD + "&angle_deg=35&limit=101", token),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(high.fieldErrors())
        .extracting("field", "code", "message")
        .containsExactly(org.assertj.core.groups.Tuple.tuple("limit", "maximum", "must be <= 100"));

    ErrorEnvelope low =
        assertError(
            get("/v1/routes" + BOARD + "&limit=0", token),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(low.fieldErrors())
        .extracting("field", "code")
        .containsExactly(org.assertj.core.groups.Tuple.tuple("limit", "minimum"));

    ErrorEnvelope type =
        assertError(
            get("/v1/routes" + BOARD + "&angle_deg=steep&grade_min=hard", token),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(type.fieldErrors()).extracting("field").containsExactly("angle_deg", "grade_min");
  }

  @Test
  void malformedCursorIs422InvalidCursor() {
    assertError(
        get("/v1/routes" + BOARD + "&cursor=not-base64!", token),
        HttpStatus.UNPROCESSABLE_CONTENT,
        ErrorCode.INVALID_CURSOR);
    String padded = java.util.Base64.getUrlEncoder().encodeToString("20.6|nope".getBytes());
    assertError(
        get("/v1/routes" + BOARD + "&cursor=" + padded, token),
        HttpStatus.UNPROCESSABLE_CONTENT,
        ErrorCode.INVALID_CURSOR);
  }

  /** Follows {@code next_cursor} to the end; every page body must validate against RouteList. */
  private List<JsonNode> walk(String firstPath) {
    List<JsonNode> items = new ArrayList<>();
    String path = firstPath;
    while (path != null) {
      ResponseEntity<String> response = get(path, token);
      assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
      ContractSchemas.assertConforms("RouteList", response.getBody());
      JsonNode page = body(response);
      page.get("items").forEach(items::add);
      JsonNode next = page.get("next_cursor");
      path = next.isNull() ? null : firstPath + "&cursor=" + next.asString();
    }
    return items;
  }

  private static int compare(VariantRow a, VariantRow b) {
    if (a.gradeNumeric() == null || b.gradeNumeric() == null) {
      if (a.gradeNumeric() != null) {
        return -1;
      }
      if (b.gradeNumeric() != null) {
        return 1;
      }
      return byId(a, b);
    }
    int byGrade = a.gradeNumeric().compareTo(b.gradeNumeric());
    return byGrade != 0 ? byGrade : byId(a, b);
  }

  /** Postgres orders uuid as unsigned bytes, which the canonical hex string reproduces. */
  private static int byId(VariantRow a, VariantRow b) {
    return a.variantId().toString().compareTo(b.variantId().toString());
  }
}
