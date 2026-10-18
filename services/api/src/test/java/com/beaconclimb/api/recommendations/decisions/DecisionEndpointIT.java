package com.beaconclimb.api.recommendations.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.recommendations.RecommendationsTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0112 getDecision: a POST round-trips through GET as a contract {@code Decision} equal to the
 * served ranking, the shipped fixture conforms, and every other case (foreign, unknown, malformed,
 * erased) is the same 404 (TM-01). The body never carries the snapshot URI or any digest.
 */
class DecisionEndpointIT extends RecommendationsTestSupport {

  @Test
  void postRoundTripsThroughGetAsAConformingDecision() {
    Account account = registerReady("INTERMEDIATE");
    UUID session = UUID.randomUUID();
    Map<String, Object> body = recommendationBody("TECHNIQUE", 5, session, List.of());
    JsonNode posted = recommendOk(account.token(), body);
    String decisionId = posted.get("decision_id").asString();

    ResponseEntity<String> response = get(DECISIONS + decisionId, account.token());

    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Decision", response.getBody());
    JsonNode decision = tree(response.getBody());
    assertThat(decision.get("decision_id").asString()).isEqualTo(decisionId);
    assertThat(decision.get("request")).isEqualTo(tree(json.writeValueAsString(body)));
    assertThat(decision.get("recommendations")).isEqualTo(posted.get("recommendations"));
    assertThat(servedIds(decision)).isEqualTo(servedIds(posted));
    assertThat(servedScoreBits(decision)).isEqualTo(servedScoreBits(posted));
    for (String member :
        List.of(
            "policy_version",
            "model_version",
            "feature_as_of",
            "features_source",
            "features_cutoff_w",
            "cold_start",
            "fallback_used")) {
      assertThat(decision.get(member)).as(member).isEqualTo(posted.get(member));
    }
    // BC-0310 part 1: the read path derives the source (SYNC) and reads the cutoff from the
    // ledger's feature_cutoff_w column; both echo what the POST served.
    assertThat(decision.get("features_source").asString()).isEqualTo("SYNC");
    assertThat(decision.get("features_cutoff_w").asString()).isNotBlank();
    assertThat(decision.get("created_at").asString()).isNotBlank();
    assertThat(response.getBody())
        .doesNotContain("candidate_snapshot_uri")
        .doesNotContain("digest")
        .doesNotContain("data/decisions")
        .doesNotContain("user_features")
        .doesNotContain("rng_seed");
  }

  @Test
  void shippedFixtureConforms() {
    ContractSchemas.assertConforms(
        "Decision", fixture("getDecision", "response.json").get("body").toString());
  }

  @Test
  void foreignUnknownMalformedAndErasedDecisionsAreAllNotFound() {
    Account owner = registerReady("NOVICE");
    Account other = registerReady("NOVICE");
    JsonNode posted =
        recommendOk(owner.token(), recommendationBody("STRENGTH", 2, UUID.randomUUID(), List.of()));
    String decisionId = posted.get("decision_id").asString();

    assertError(
        get(DECISIONS + decisionId, other.token()), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
    assertError(
        get(DECISIONS + UUID.randomUUID(), owner.token()),
        HttpStatus.NOT_FOUND,
        ErrorCode.NOT_FOUND);
    assertError(
        get(DECISIONS + "not-a-uuid", owner.token()), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
    assertError(
        get(DECISIONS + decisionId.toUpperCase(), owner.token()),
        HttpStatus.NOT_FOUND,
        ErrorCode.NOT_FOUND);
    assertError(
        get(DECISIONS + decisionId, null), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
    assertThat(get(DECISIONS + decisionId, owner.token()).getStatusCode()).isEqualTo(HttpStatus.OK);

    markErased(UUID.fromString(decisionId));

    assertError(
        get(DECISIONS + decisionId, owner.token()), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
  }

  /**
   * The app role cannot UPDATE the ledger (TM-24), so the erasure flag is set as the owner role.
   */
  private void markErased(UUID decisionId) {
    org.springframework.jdbc.core.simple.JdbcClient migrate =
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "beacon_migrate", MIGRATE_PASSWORD));
    assertThat(
            migrate
                .sql("UPDATE recommendation_decisions SET erased = true WHERE decision_id = :id")
                .param("id", decisionId)
                .update())
        .isEqualTo(1);
  }
}
