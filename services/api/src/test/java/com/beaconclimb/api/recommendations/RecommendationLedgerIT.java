package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.platform.json.Jcs;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

/**
 * BC-0111 ledger acceptance (ADR-0003): the decision row's digest is the sha256 of the stored file,
 * the file is canonical JSON that validates as a CandidateSnapshot, record_json validates as a
 * FullRecord, exposures mirror the served list, the seeds and digests are reproducible 64-hex, the
 * snapshot carries no notes or attempt ids, and the runtime role cannot rewrite the ledger (TM-24).
 */
class RecommendationLedgerIT extends RecommendationsTestSupport {

  private static final String HEX64 = "^[0-9a-f]{64}$";

  @Test
  void decisionRowDigestMatchesTheCanonicalSnapshotFileAndBothValidate() {
    Account account = registerReady("INTERMEDIATE");
    UUID session = UUID.randomUUID();
    JsonNode response =
        recommendOk(account.token(), recommendationBody("TECHNIQUE", 6, session, List.of()));
    UUID decisionId = UUID.fromString(response.get("decision_id").asString());

    Map<String, Object> row = decisionRow(decisionId);
    String digest = (String) row.get("candidate_set_digest");
    byte[] file = snapshotFile(digest);
    String fileText = new String(file, StandardCharsets.UTF_8);

    assertThat(digest).matches(HEX64).isEqualTo(Jcs.sha256Hex(file));
    assertThat(Jcs.canonicalize(fileText)).isEqualTo(fileText);
    assertThat(DecisionContractSchema.validateSnapshot(fileText)).isEmpty();
    assertThat(DecisionContractSchema.validateRoot(fileText)).isEmpty();
    assertThat(recordSchema.validateSnapshot(tree(fileText))).isEmpty();
    assertThat(row.get("candidate_snapshot_uri"))
        .isEqualTo("data/decisions/" + digest.substring(0, 2) + "/" + digest + ".json");
    assertThat(store.get(digest)).contains(file);

    String recordText = (String) row.get("record_json");
    assertThat(DecisionContractSchema.validateRecord(recordText)).isEmpty();
    assertThat(DecisionContractSchema.validateRoot(recordText)).isEmpty();
    JsonNode record = tree(recordText);
    assertThat(record.get("decision_id").asString()).isEqualTo(decisionId.toString());
    assertThat(record.get("user_id").asString()).isEqualTo(account.userId().toString());
    assertThat(record.get("session_id").asString()).isEqualTo(session.toString());
    assertThat(record.get("candidate_set_digest").asString()).isEqualTo(digest);
    assertThat(record.get("candidate_snapshot_uri").asString())
        .isEqualTo(row.get("candidate_snapshot_uri"));
    assertThat(record.get("model_version").asString()).isEqualTo("none");
    assertThat(record.get("model_artifact_digest").isNull()).isTrue();
    assertThat(record.get("erased").asBoolean()).isFalse();
    assertThat(record.get("policy_code_digest").asString())
        .isEqualTo(row.get("policy_code_digest"));
    assertThat(record.get("feature_snapshot_id").asString())
        .isEqualTo(row.get("feature_snapshot_user_id").toString());
    assertThat(record.get("ranked_results")).hasSize(6);
    assertThat(record.get("explanation").size()).isEqualTo(6);
    JsonNode request = tree((String) row.get("request_json"));
    assertThat(request.get("session_id").asString()).isEqualTo(session.toString());
    assertThat(request.get("max_routes").asInt()).isEqualTo(6);
    assertThat(row.get("erased")).isEqualTo(false);
    assertThat(row.get("fallback_used")).isEqualTo(false);
    assertThat(row.get("model_version")).isEqualTo("none");
  }

  @Test
  void exposuresMirrorTheServedListPositionByPosition() {
    Account account = registerReady("EXPERT");
    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("PROJECT", 7, UUID.randomUUID(), List.of()));
    UUID decisionId = UUID.fromString(response.get("decision_id").asString());

    List<Map<String, Object>> exposures = exposureRows(decisionId);

    JsonNode served = response.get("recommendations");
    assertThat(exposures).hasSize(served.size());
    for (int i = 0; i < served.size(); i++) {
      Map<String, Object> exposure = exposures.get(i);
      JsonNode item = served.get(i);
      assertThat(exposure.get("position")).isEqualTo(item.get("position").asInt());
      assertThat(exposure.get("route_variant_id"))
          .isEqualTo(item.get("route_variant_id").asString());
      assertThat(Double.doubleToRawLongBits((Double) exposure.get("rank_score")))
          .isEqualTo(Double.doubleToRawLongBits(item.get("rank_score").doubleValue()));
      assertThat(tree((String) exposure.get("reasons"))).isEqualTo(item.get("reason_codes"));
    }
  }

  @Test
  void seedAndEnvironmentDigestAreHexAndReproducible() {
    Account account = registerReady("INTERMEDIATE");
    UUID session = UUID.randomUUID();
    Map<String, Object> body = recommendationBody("STRENGTH", 3, session, List.of());

    Map<String, Object> first =
        decisionRow(
            UUID.fromString(recommendOk(account.token(), body).get("decision_id").asString()));
    Map<String, Object> second =
        decisionRow(
            UUID.fromString(recommendOk(account.token(), body).get("decision_id").asString()));

    String seed = (String) first.get("rng_seed");
    assertThat(seed)
        .matches(HEX64)
        .isEqualTo(ReplayDigests.rngSeed("heuristic-v2", account.userId(), session))
        .isEqualTo(
            Jcs.sha256Hex(
                ("heuristic-v2|" + account.userId() + "|" + session)
                    .getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(second.get("rng_seed"));
    String environment = (String) first.get("environment_digest");
    assertThat(environment)
        .matches(HEX64)
        .isEqualTo(
            ReplayDigests.environmentDigest(
                (String) first.get("policy_code_digest"), ReplayDigests.RANKING_IMPLEMENTATION_V2))
        .isEqualTo(second.get("environment_digest"));
    assertThat((String) first.get("policy_code_digest")).matches(HEX64);
    assertThat(first.get("candidate_set_digest")).isEqualTo(second.get("candidate_set_digest"));
  }

  @Test
  void snapshotHoldsNoNotesOrAttemptIds() {
    Account account = registerReady("BEGINNER");
    String attemptId =
        attemptAt(account, candidateIds().get(2), java.time.Instant.now().minusSeconds(3600));
    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of()));
    Map<String, Object> row = decisionRow(UUID.fromString(response.get("decision_id").asString()));

    String snapshot = new String(snapshotFile((String) row.get("candidate_set_digest")));
    JsonNode features = tree(snapshot).get("user_features");

    assertThat(snapshot).doesNotContain("notes").doesNotContain(attemptId);
    assertThat(features.get("recent_variant_ids")).hasSize(1);
    assertThat(features.get("target_grade_numeric").isNumber()).isTrue();
    assertThat(tree(snapshot).get("candidates").size()).isEqualTo(candidateIds().size());
  }

  @Test
  void appRoleCannotUpdateOrDeleteTheLedger() {
    Account account = registerReady("NOVICE");
    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("ENDURANCE", 2, UUID.randomUUID(), List.of()));
    UUID decisionId = UUID.fromString(response.get("decision_id").asString());

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "UPDATE recommendation_decisions SET cold_start = false WHERE decision_id = :id")
                    .param("id", decisionId)
                    .update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM recommendation_exposures WHERE decision_id = :id")
                    .param("id", decisionId)
                    .update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThat(exposureRows(decisionId)).hasSize(2);
  }
}
