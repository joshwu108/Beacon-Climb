package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * listAttempts (contract A11): newest first by occurred_at then attempt_id, a cursor walk over 25
 * attempts at limit 10 with no gaps or duplicates, the status filter, parameter and cursor
 * failures, the route summary on every item, and strict per-user scoping.
 */
class AttemptListIT extends AttemptsTestSupport {

  private static final int TOTAL = 25;
  private static final Instant BASE = Instant.parse("2026-10-14T14:00:00Z");

  @Test
  void cursorWalksNewestFirstWithoutGapsAndStatusFilters() {
    Account account = registerWithConsent();
    List<String> routes = visibleRouteIds(3);
    Set<String> created = new HashSet<>();
    for (int i = 0; i < TOTAL; i++) {
      // Three attempts share one occurred_at so the attempt_id tiebreak is exercised.
      Instant occurredAt = BASE.minusSeconds(60L * (i < 3 ? 0 : i));
      ResponseEntity<String> response =
          postAttempt(
              account.token(),
              "k-list-" + i,
              attemptBody(routes.get(i % routes.size()), occurredAt));
      assertThat(response.getStatusCode()).as("attempt %d", i).isEqualTo(HttpStatus.CREATED);
      created.add(tree(response.getBody()).get("attempt_id").asString());
    }
    List<String> retracted = new ArrayList<>(created).subList(0, 2);
    for (String id : retracted) {
      assertThat(patchAttempt(account.token(), id, "\"1\"", "{\"retract\":true}").getStatusCode())
          .isEqualTo(HttpStatus.OK);
    }

    List<JsonNode> walked = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      String path = ATTEMPTS + "?limit=10" + (cursor == null ? "" : "&cursor=" + cursor);
      ResponseEntity<String> page = get(path, account.token());
      assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
      ContractSchemas.assertConforms("AttemptList", page.getBody());
      JsonNode body = tree(page.getBody());
      body.get("items").forEach(walked::add);
      cursor = body.get("next_cursor").isNull() ? null : body.get("next_cursor").asString();
      pages++;
      assertThat(body.get("items").size()).isEqualTo(cursor == null ? 5 : 10);
    } while (cursor != null);

    assertThat(pages).isEqualTo(3);
    assertThat(walked).hasSize(TOTAL);
    assertThat(
            walked.stream()
                .map(n -> n.get("attempt_id").asString())
                .collect(java.util.stream.Collectors.toSet()))
        .isEqualTo(created);
    for (int i = 1; i < walked.size(); i++) {
      Instant previous = Instant.parse(walked.get(i - 1).get("occurred_at").asString());
      Instant current = Instant.parse(walked.get(i).get("occurred_at").asString());
      assertThat(current).isBeforeOrEqualTo(previous);
      if (current.equals(previous)) {
        assertThat(walked.get(i).get("attempt_id").asString())
            .isLessThan(walked.get(i - 1).get("attempt_id").asString());
      }
      assertThat(walked.get(i).get("route").get("route_variant_id").asString())
          .isEqualTo(walked.get(i).get("route_variant_id").asString());
    }

    JsonNode onlyRetracted = tree(get(ATTEMPTS + "?status=RETRACTED", account.token()).getBody());
    assertThat(onlyRetracted.get("items"))
        .extracting(n -> n.get("attempt_id").asString())
        .containsExactlyInAnyOrderElementsOf(retracted);
    assertThat(onlyRetracted.get("items"))
        .allSatisfy(n -> assertThat(n.get("status").asString()).isEqualTo("RETRACTED"));
    JsonNode onlyActive =
        tree(get(ATTEMPTS + "?status=ACTIVE&limit=100", account.token()).getBody());
    assertThat(onlyActive.get("items")).hasSize(TOTAL - 2);
    JsonNode defaults = tree(get(ATTEMPTS, account.token()).getBody());
    assertThat(defaults.get("items")).hasSize(AttemptQuery.DEFAULT_LIMIT);
    assertThat(defaults.get("next_cursor").isNull()).isFalse();
  }

  @Test
  void invalidCursorAndParametersAreContractErrors() {
    Account account = registerWithConsent();

    assertError(
        get(ATTEMPTS + "?cursor=%21%21%21", account.token()),
        HttpStatus.UNPROCESSABLE_CONTENT,
        ErrorCode.INVALID_CURSOR);
    assertError(
        get(
            ATTEMPTS + "?cursor=" + new AttemptCursor(BASE, UUID.randomUUID()).encode() + "=",
            account.token()),
        HttpStatus.UNPROCESSABLE_CONTENT,
        ErrorCode.INVALID_CURSOR);
    assertThat(
            assertError(
                    get(ATTEMPTS + "?limit=0&status=FOO", account.token()),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactlyInAnyOrder(Tuple.tuple("limit", "minimum"), Tuple.tuple("status", "enum"));
    assertThat(
            assertError(
                    get(ATTEMPTS + "?limit=101", account.token()),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::code)
        .containsExactly("maximum");
    assertThat(
            assertError(
                    get(ATTEMPTS + "?limit=abc", account.token()),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::code)
        .containsExactly("type");

    ResponseEntity<String> empty = get(ATTEMPTS, account.token());
    assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("AttemptList", empty.getBody());
    assertThat(tree(empty.getBody()).get("items")).isEmpty();
    assertThat(tree(empty.getBody()).get("next_cursor").isNull()).isTrue();
  }

  @Test
  void listsAreScopedToTheAuthenticatedUserAndNeedNoConsent() {
    Account owner = registerWithConsent();
    Account other = registerWithoutConsent();
    String attemptId = createAttempt(owner, visibleRouteId());

    JsonNode mine = tree(get(ATTEMPTS, owner.token()).getBody());
    assertThat(mine.get("items"))
        .extracting(n -> n.get("attempt_id").asString())
        .containsExactly(attemptId);
    ContractSchemas.assertConforms("Attempt", json.writeValueAsString(mine.get("items").get(0)));
    assertThat(mine.get("items").get(0).get("route").get("board_id").asString())
        .isEqualTo("synthetic-demo-1");

    ResponseEntity<String> theirs = get(ATTEMPTS, other.token());
    assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(tree(theirs.getBody()).get("items")).isEmpty();
    assertError(get(ATTEMPTS, null), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
  }
}
