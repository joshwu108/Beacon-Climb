package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import com.beaconclimb.api.platform.web.FieldError;
import com.beaconclimb.api.ranking.TieBreak;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0111 createRecommendations over HTTP against the seeded demo board: contract conformance of
 * the live response and the shipped fixtures, the MVP constants, determinism per session id
 * (TM-09), exclusions, the cooldown filter, cold start before and after an attempt, and every
 * declared error status.
 */
class RecommendationsEndpointIT extends RecommendationsTestSupport {

  private static final Instant T = Instant.parse("2026-10-14T14:00:00Z");

  @BeforeEach
  void pinClock() {
    testClock.fix(T);
  }

  @Test
  void happyPathConformsAndCarriesTheMvpConstants() {
    Account account = registerReady("INTERMEDIATE");

    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 5, UUID.randomUUID(), List.of()));

    assertThat(response.get("policy_version").asString()).isEqualTo("heuristic-v1");
    assertThat(response.get("model_version").asString()).isEqualTo("none");
    assertThat(response.get("fallback_used").asBoolean()).isFalse();
    assertThat(response.get("cold_start").asBoolean()).isTrue();
    assertThat(response.get("feature_as_of").asString()).isEqualTo("2026-10-14T14:00:00Z");
    JsonNode items = response.get("recommendations");
    assertThat(items).hasSize(5);
    for (int i = 0; i < items.size(); i++) {
      JsonNode item = items.get(i);
      assertThat(item.get("position").asInt()).isEqualTo(i + 1);
      assertThat(item.get("predicted_send_probability").isNull()).isTrue();
      assertThat(item.get("uncertainty_label").asString()).isEqualTo("INSUFFICIENT_HISTORY");
      assertThat(item.get("route").get("route_variant_id").asString())
          .isEqualTo(item.get("route_variant_id").asString());
      assertThat(item.get("route").get("angle_deg").asInt()).isEqualTo(ANGLE);
      assertThat(item.get("reason_codes").valueStream().map(JsonNode::asString))
          .contains("COMPATIBLE_ANGLE", "COLD_START_HEURISTIC");
    }
    assertThat(servedIds(response)).doesNotHaveDuplicates().isSubsetOf(candidateIds());
  }

  @Test
  void shippedFixturesConformAndThe422FixtureReplaysExactly() {
    ContractSchemas.assertConforms(
        "RecommendationRequest",
        fixture("createRecommendations", "request.json").get("body").toString());
    ContractSchemas.assertConforms(
        "RecommendationResponse",
        fixture("createRecommendations", "response.json").get("body").toString());
    ContractSchemas.assertConforms(
        "ErrorEnvelope",
        fixture("createRecommendations", "response-422.json").get("body").toString());
    Account account = registerReady("BEGINNER");

    ResponseEntity<String> live =
        post(
            RECOMMENDATIONS,
            fixture("createRecommendations", "request-422.json").get("body").toString(),
            account.token());

    ErrorEnvelope envelope =
        assertError(live, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);
    JsonNode expected = fixture("createRecommendations", "response-422.json").get("body");
    assertThat(envelope.message()).isEqualTo(expected.get("message").asString());
    assertThat(tree(live.getBody()).get("field_errors")).isEqualTo(expected.get("field_errors"));
  }

  @Test
  void sameSessionAndContextGiveIdenticalRankingUnderNewDecisionIds() {
    Account account = registerReady("ADVANCED");
    UUID session = UUID.randomUUID();
    Map<String, Object> body = recommendationBody("STRENGTH", 8, session, List.of());

    JsonNode first = recommendOk(account.token(), body);
    JsonNode second = recommendOk(account.token(), body);

    assertThat(servedIds(second)).isEqualTo(servedIds(first));
    assertThat(servedScoreBits(second)).isEqualTo(servedScoreBits(first));
    assertThat(second.get("decision_id").asString())
        .isNotEqualTo(first.get("decision_id").asString());
  }

  @Test
  void differentSessionIdMovesIdsOnlyAmongEqualScoresAndTiesFollowEachSessionsKey() {
    Account account = registerReady("ADVANCED");
    UUID sessionA = UUID.randomUUID();
    UUID sessionB = UUID.randomUUID();

    JsonNode first =
        recommendOk(account.token(), recommendationBody("STRENGTH", 8, sessionA, List.of()));
    JsonNode second =
        recommendOk(account.token(), recommendationBody("STRENGTH", 8, sessionB, List.of()));

    // Scores do not depend on the session id: position by position the score bits agree, so any
    // id that differs between the sessions moved among equal scores only, and within each session
    // every run of equal scores is in ascending sha256 tie order (ADR-0003, TieBreak.key).
    List<String> idsA = servedIds(first);
    List<String> idsB = servedIds(second);
    List<Long> bitsA = servedScoreBits(first);
    List<Long> bitsB = servedScoreBits(second);
    assertThat(idsB).hasSameSizeAs(idsA);
    assertThat(bitsB).isEqualTo(bitsA);
    for (int i = 0; i < idsA.size(); i++) {
      if (!idsA.get(i).equals(idsB.get(i))) {
        assertThat(bitsB.get(i)).as("position %d", i + 1).isEqualTo(bitsA.get(i));
        assertThat(idsB).contains(idsA.get(i));
      }
    }
    assertTiesFollowTheSessionKey(account.userId(), sessionA, idsA, bitsA);
    assertTiesFollowTheSessionKey(account.userId(), sessionB, idsB, bitsB);
  }

  private static void assertTiesFollowTheSessionKey(
      UUID userId, UUID sessionId, List<String> ids, List<Long> bits) {
    for (int i = 1; i < ids.size(); i++) {
      if (bits.get(i).equals(bits.get(i - 1))) {
        assertThat(TieBreak.key("heuristic-v1", userId, sessionId, ids.get(i - 1)))
            .as("session %s positions %d and %d", sessionId, i, i + 1)
            .isLessThan(TieBreak.key("heuristic-v1", userId, sessionId, ids.get(i)));
      }
    }
  }

  @Test
  void excludedIdsNeverAppear() {
    Account account = registerReady("INTERMEDIATE");
    JsonNode baseline =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 5, UUID.randomUUID(), List.of()));
    List<String> exclude = servedIds(baseline).subList(0, 3);

    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 5, UUID.randomUUID(), exclude));

    assertThat(servedIds(response)).doesNotContainAnyElementsOf(exclude).hasSize(5);
  }

  @Test
  void routeAttemptedAnHourAgoIsSkippedByCooldownUnlessGoalIsProject() {
    Account account = registerReady("INTERMEDIATE");
    String attempted = candidateIds().get(0);
    attemptAt(account, attempted, T.minus(Duration.ofHours(1)));

    JsonNode technique =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 20, UUID.randomUUID(), List.of()));
    JsonNode project =
        recommendOk(
            account.token(), recommendationBody("PROJECT", 20, UUID.randomUUID(), List.of()));

    assertThat(servedIds(technique)).doesNotContain(attempted);
    JsonNode cooled = candidateIn(technique, attempted);
    assertThat(cooled.get("eligible").asBoolean()).isFalse();
    assertThat(cooled.get("filter_results").get("cooldown").asBoolean()).isFalse();
    assertThat(cooled.get("features").get("cooldown_active").asBoolean()).isTrue();
    JsonNode allowed = candidateIn(project, attempted);
    assertThat(allowed.get("eligible").asBoolean()).isTrue();
    assertThat(allowed.get("filter_results").get("cooldown").asBoolean()).isTrue();
    assertThat(allowed.get("features").get("cooldown_active").asBoolean()).isTrue();
  }

  @Test
  void coldStartIsTrueForANewUserAndFalseAfterAnAttempt() {
    Account account = registerReady("BEGINNER");
    JsonNode before =
        recommendOk(
            account.token(), recommendationBody("ENDURANCE", 4, UUID.randomUUID(), List.of()));
    Instant occurredAt = T.minus(Duration.ofHours(3));
    attemptAt(account, candidateIds().get(1), occurredAt);

    JsonNode after =
        recommendOk(
            account.token(), recommendationBody("ENDURANCE", 4, UUID.randomUUID(), List.of()));

    assertThat(before.get("cold_start").asBoolean()).isTrue();
    assertThat(after.get("cold_start").asBoolean()).isFalse();
    assertThat(after.get("feature_as_of").asString()).isEqualTo(occurredAt.toString());
    assertThat(after.get("recommendations").get(0).get("reason_codes").valueStream())
        .extracting(JsonNode::asString)
        .doesNotContain("COLD_START_HEURISTIC");
    Map<String, Object> row = decisionRow(UUID.fromString(after.get("decision_id").asString()));
    assertThat(row.get("cold_start")).isEqualTo(false);
    assertThat(row.get("feature_as_of")).isNotNull();
  }

  @Test
  void withoutConsentIsForbidden() {
    Account account = registerWithoutConsent();
    setPreferences(account.token(), "NOVICE");

    assertError(
        recommend(
            account.token(), recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of())),
        HttpStatus.FORBIDDEN,
        ErrorCode.CONSENT_REQUIRED);
  }

  @Test
  void unknownBoardAndUnsupportedAngleAre422() {
    Account account = registerReady("NOVICE");
    Map<String, Object> unknown = recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of());
    unknown.put("board_id", "no-such-board");
    Map<String, Object> badAngle = recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of());
    badAngle.put("angle_deg", 35);

    ErrorEnvelope board =
        assertError(
            recommend(account.token(), unknown),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.UNKNOWN_BOARD);
    ErrorEnvelope angle =
        assertError(
            recommend(account.token(), badAngle),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.UNSUPPORTED_ANGLE);

    assertThat(board.fieldErrors())
        .containsExactly(new FieldError("board_id", "unknown", "no such board"));
    assertThat(angle.fieldErrors())
        .containsExactly(new FieldError("angle_deg", "enum", "must be one of 20, 30, 40, 45, 50"));
  }

  @Test
  void missingPreferencesIs422WithAPreferencesFieldError() {
    Account account = registerWithConsent();

    ErrorEnvelope envelope =
        assertError(
            recommend(
                account.token(), recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of())),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);

    assertThat(envelope.fieldErrors())
        .containsExactly(
            new FieldError(
                "preferences", "missing", "Set preferences before requesting recommendations"));
  }

  @Test
  void shapeViolationsAreReportedTogetherAndUnknownMembersAreUnknownField() {
    Account account = registerReady("NOVICE");
    String bad =
        json.writeValueAsString(
            Map.of(
                "board_id",
                7,
                "angle_deg",
                "forty",
                "goal",
                "NAPPING",
                "max_routes",
                21,
                "session_id",
                "not-a-uuid",
                "exclude_route_variant_ids",
                List.of("no colon", 3)));
    Map<String, Object> extra = recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of());
    extra.put("notes", "x");

    ErrorEnvelope shape =
        assertError(
            post(RECOMMENDATIONS, bad, account.token()),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    ErrorEnvelope unknown =
        assertError(
            recommend(account.token(), extra),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.UNKNOWN_FIELD);
    ErrorEnvelope missing =
        assertError(
            post(RECOMMENDATIONS, "{}", account.token()),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);

    assertThat(shape.fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("board_id", "type"),
            org.assertj.core.groups.Tuple.tuple("angle_deg", "type"),
            org.assertj.core.groups.Tuple.tuple("goal", "enum"),
            org.assertj.core.groups.Tuple.tuple("max_routes", "maximum"),
            org.assertj.core.groups.Tuple.tuple("session_id", "format"),
            org.assertj.core.groups.Tuple.tuple("exclude_route_variant_ids[0]", "pattern"),
            org.assertj.core.groups.Tuple.tuple("exclude_route_variant_ids[1]", "type"));
    assertThat(unknown.fieldErrors()).extracting(FieldError::field).containsExactly("notes");
    assertThat(missing.fieldErrors())
        .extracting(FieldError::field)
        .containsExactly(
            "board_id",
            "angle_deg",
            "goal",
            "max_routes",
            "session_id",
            "exclude_route_variant_ids");
    assertThat(missing.fieldErrors()).allMatch(error -> error.code().equals("required"));
  }

  @Test
  void requiresAuthentication() {
    assertError(
        recommend(null, recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of())),
        HttpStatus.UNAUTHORIZED,
        ErrorCode.UNAUTHENTICATED);
  }

  private JsonNode candidateIn(JsonNode response, String routeVariantId) {
    Map<String, Object> row = decisionRow(UUID.fromString(response.get("decision_id").asString()));
    JsonNode snapshot = tree(new String(snapshotFile((String) row.get("candidate_set_digest"))));
    return snapshot
        .get("candidates")
        .valueStream()
        .filter(c -> c.get("route_variant_id").asString().equals(routeVariantId))
        .findFirst()
        .orElseThrow();
  }
}
