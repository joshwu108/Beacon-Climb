package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.modeloutputs.ModelOutputRepository;
import com.beaconclimb.api.ranking.TieBreak;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0210 heuristic-v2 end to end on the synthetic demo board with SYNTHETIC model outputs seeded
 * into model_versions / route_model_outputs under the version names heuristic-v2 cites. Checks the
 * owner's decisions (2026-10-19): the ascent-weighted grade blend with k = min_obs, embedding
 * novelty, the v1.1 record fields, v1 still selectable, a bit-for-bit replay from the stored record
 * and snapshot, and "more like this". Outputs are deleted and the cache cleared after each test so
 * other classes keep seeing a board without model outputs.
 */
class ModelAwareRecommendationsIT extends RecommendationsTestSupport {

  private static final Instant T = Instant.parse("2026-10-14T14:00:00Z");
  private static final String GRADE_MODEL = "model-a-gbm-20261019";
  private static final String EMBEDDING_MODEL = "model-a-deepsets-20261019";
  private static final String GRADE_DIGEST = "a".repeat(64);
  private static final String EMBEDDING_DIGEST = "b".repeat(64);
  private static final int DIM = 32;
  private static final double BLEND_K = 10;
  private static final double NOVELTY_SCALE = 0.5;

  @Autowired ModelOutputRepository outputs;

  private final Map<String, Double> predicted = new LinkedHashMap<>();
  private final Map<String, float[]> vectors = new LinkedHashMap<>();

  @BeforeEach
  void seedModelOutputs() {
    testClock.fix(T);
    for (String version : List.of(GRADE_MODEL, EMBEDDING_MODEL)) {
      jdbc.sql(
              "INSERT INTO model_versions (model_version, family, kind, artifact_digest,"
                  + " input_schema_version, training_manifest_id, training_run_id, metrics_ref,"
                  + " license_review_status, status) VALUES (:v, 'model-a', :kind, :digest,"
                  + " 'model-a-features-v1', 'model-a-000000000000', 'it', 'docs/x', 'SYNTHETIC',"
                  + " 'READY') ON CONFLICT (model_version) DO NOTHING")
          .param("v", version)
          .param("kind", version.equals(GRADE_MODEL) ? "sklearn-hgb" : "torch-deepsets")
          .param("digest", version.equals(GRADE_MODEL) ? GRADE_DIGEST : EMBEDDING_DIGEST)
          .update();
    }
    List<String> ids = candidateIds();
    for (int i = 0; i < ids.size(); i++) {
      String id = ids.get(i);
      double grade = 12 + (i % 15);
      float[] vector = new float[DIM];
      for (int j = 0; j < DIM; j++) {
        vector[j] = (float) Math.cos(0.37 * i + 0.11 * j * (1 + i % 3));
      }
      predicted.put(id, grade);
      vectors.put(id, vector);
      insertOutput(id, GRADE_MODEL, grade, null);
      insertOutput(id, EMBEDDING_MODEL, grade, vector);
    }
    outputs.clearCache();
  }

  @AfterEach
  void removeModelOutputs() {
    jdbc.sql("DELETE FROM route_model_outputs WHERE model_version IN (:a, :b)")
        .param("a", GRADE_MODEL)
        .param("b", EMBEDDING_MODEL)
        .update();
    outputs.clearCache();
  }

  @Test
  void defaultIsHeuristicV2AndTheDecisionNamesBothModels() {
    Account account = registerReady("INTERMEDIATE");

    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 8, UUID.randomUUID(), List.of()));

    assertThat(response.get("policy_version").asString()).isEqualTo("heuristic-v2");
    assertThat(response.get("model_version").asString()).isEqualTo(GRADE_MODEL);
    JsonNode record = record(response);
    assertThat(recordSchema.validateRecord(record)).isEmpty();
    assertThat(record.get("model_artifact_digest").asString()).isEqualTo(GRADE_DIGEST);
    assertThat(record.get("model_artifacts").get(0).get("role").asString()).isEqualTo("grade");
    assertThat(record.get("model_artifacts").get(1).get("model_version").asString())
        .isEqualTo(EMBEDDING_MODEL);
    assertThat(record.get("model_artifacts").get(1).get("artifact_digest").asString())
        .isEqualTo(EMBEDDING_DIGEST);
  }

  @Test
  void snapshotCarriesTheBlendedGradeExactlyAsDecided() {
    Account account = registerReady("INTERMEDIATE");

    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 5, UUID.randomUUID(), List.of()));

    JsonNode snapshot = snapshot(response);
    assertThat(recordSchema.validateSnapshot(snapshot)).isEmpty();
    for (JsonNode candidate : snapshot.get("candidates")) {
      String id = candidate.get("route_variant_id").asString();
      JsonNode features = candidate.get("features");
      JsonNode model = candidate.get("model_outputs");
      double community = features.get("grade_numeric").doubleValue();
      double n = features.get("grade_observation_count").intValue();
      double numerator = n * community + BLEND_K * predicted.get(id);
      assertThat(model.get("predicted_grade").doubleValue()).isEqualTo(predicted.get(id));
      assertThat(Double.doubleToRawLongBits(model.get("grade_used").doubleValue()))
          .as(id)
          .isEqualTo(Double.doubleToRawLongBits(numerator / (n + BLEND_K)));
      // cold start: no recent routes, so no distance and full novelty
      assertThat(model.get("embedding_distance").isNull()).isTrue();
      assertThat(features.get("novelty").doubleValue()).isEqualTo(1.0);
    }
  }

  @Test
  void warmNoveltyFollowsTheNearestRecentEmbedding() {
    Account account = registerReady("ADVANCED");
    List<String> pool = candidateIds();
    attemptAt(account, pool.get(0), T.minus(Duration.ofDays(3)));
    attemptAt(account, pool.get(1), T.minus(Duration.ofDays(4)));

    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("STRENGTH", 10, UUID.randomUUID(), List.of()));

    JsonNode snapshot = snapshot(response);
    List<String> recent = new ArrayList<>();
    snapshot.get("user_features").get("recent_variant_ids").forEach(v -> recent.add(v.asString()));
    assertThat(recent).containsExactlyInAnyOrder(pool.get(0), pool.get(1));
    for (JsonNode candidate : snapshot.get("candidates")) {
      String id = candidate.get("route_variant_id").asString();
      double novelty = candidate.get("features").get("novelty").doubleValue();
      JsonNode distance = candidate.get("model_outputs").get("embedding_distance");
      double expected = Double.POSITIVE_INFINITY;
      for (String r : recent) {
        expected = Math.min(expected, cosine(vectors.get(id), vectors.get(r)));
      }
      assertThat(distance.doubleValue()).as(id).isEqualTo(expected);
      if (recent.contains(id)) {
        assertThat(novelty).isZero();
      } else {
        assertThat(novelty).as(id).isEqualTo(Math.min(1.0, expected / NOVELTY_SCALE));
      }
    }
  }

  @Test
  void v2DecisionReplaysBitForBitFromTheStoredRecordAndSnapshot() {
    Account account = registerReady("INTERMEDIATE");
    List<String> pool = candidateIds();
    attemptAt(account, pool.get(2), T.minus(Duration.ofDays(5)));

    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 10, UUID.randomUUID(), List.of()));

    JsonNode record = record(response);
    JsonNode snapshot = snapshot(response);
    JsonNode config = record.get("policy_config");
    JsonNode w = config.get("weights");
    double target = snapshot.get("user_features").get("target_grade_numeric").doubleValue();
    double window = config.get("grade_window").doubleValue();
    int minObs = config.get("min_obs").intValue();
    Map<String, Double> replayed = new HashMap<>();
    for (JsonNode candidate : snapshot.get("candidates")) {
      if (!candidate.get("eligible").booleanValue()) {
        continue;
      }
      JsonNode f = candidate.get("features");
      double gradeUsed = candidate.get("model_outputs").get("grade_used").doubleValue();
      double challenge = 1.0 - Math.min(1.0, Math.abs(gradeUsed - target) / window);
      double confidence =
          Math.min(1.0, (double) f.get("grade_observation_count").intValue() / minObs);
      double score = w.get("challenge").doubleValue() * challenge;
      score = score + w.get("skill_gap").doubleValue() * 0.0;
      score = score + w.get("novelty").doubleValue() * f.get("novelty").doubleValue();
      score = score + w.get("preference").doubleValue() * f.get("preference_match").doubleValue();
      score = score + w.get("confidence").doubleValue() * confidence;
      replayed.put(candidate.get("route_variant_id").asString(), score);
    }
    UUID session = UUID.fromString(record.get("session_id").asString());
    List<String> order = new ArrayList<>(replayed.keySet());
    order.sort(
        (a, b) -> {
          int byScore = Double.compare(replayed.get(b), replayed.get(a));
          return byScore != 0
              ? byScore
              : TieBreak.key("heuristic-v2", account.userId(), session, a)
                  .compareTo(TieBreak.key("heuristic-v2", account.userId(), session, b));
        });
    JsonNode ranked = record.get("ranked_results");
    assertThat(ranked).hasSize(10);
    for (int i = 0; i < ranked.size(); i++) {
      JsonNode item = ranked.get(i);
      String id = item.get("route_variant_id").asString();
      assertThat(id).as("position %d", i + 1).isEqualTo(order.get(i));
      assertThat(Double.doubleToRawLongBits(item.get("score").doubleValue()))
          .as(id)
          .isEqualTo(Double.doubleToRawLongBits(replayed.get(id)));
    }
  }

  @Test
  void heuristicV1StaysSelectableAndReadsNoModel() {
    Account account = registerReady("INTERMEDIATE");
    Map<String, Object> body = recommendationBody("TECHNIQUE", 5, UUID.randomUUID(), List.of());
    body.put("policy_version", "heuristic-v1");

    JsonNode response = recommendOk(account.token(), body);

    assertThat(response.get("policy_version").asString()).isEqualTo("heuristic-v1");
    assertThat(response.get("model_version").asString()).isEqualTo("none");
    JsonNode record = record(response);
    assertThat(record.has("model_artifacts")).isFalse();
    assertThat(record.get("model_artifact_digest").isNull()).isTrue();
    for (JsonNode candidate : snapshot(response).get("candidates")) {
      assertThat(candidate.get("model_outputs").has("grade_used")).isFalse();
      assertThat(candidate.get("model_outputs").get("predicted_grade").isNull()).isTrue();
    }
    UUID decisionId = UUID.fromString(response.get("decision_id").asString());
    assertThat(
            tree((String) decisionRow(decisionId).get("request_json"))
                .get("policy_version")
                .asString())
        .isEqualTo("heuristic-v1");
  }

  @Test
  void unknownPolicyVersionIs422() {
    Account account = registerReady("INTERMEDIATE");
    Map<String, Object> body = recommendationBody("TECHNIQUE", 5, UUID.randomUUID(), List.of());
    body.put("policy_version", "heuristic-v9");

    ResponseEntity<String> response = recommend(account.token(), body);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(response.getBody()).contains("policy_version");
  }

  @Test
  void similarRoutesAreTheNearestEmbeddingsInDistanceOrder() {
    Account account = registerReady("INTERMEDIATE");
    String id = candidateIds().get(4);

    ResponseEntity<String> response = get("/v1/routes/" + id + "/similar?limit=5", account.token());

    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("SimilarRoutes", response.getBody());
    JsonNode body = tree(response.getBody());
    assertThat(body.get("model_version").asString()).isEqualTo(EMBEDDING_MODEL);
    List<Map.Entry<String, Double>> expected = new ArrayList<>();
    vectors.forEach(
        (other, vector) -> {
          if (!other.equals(id)) {
            expected.add(Map.entry(other, cosine(vectors.get(id), vector)));
          }
        });
    expected.sort(
        Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
    JsonNode items = body.get("items");
    assertThat(items).hasSize(5);
    for (int i = 0; i < 5; i++) {
      assertThat(items.get(i).get("route").get("route_variant_id").asString())
          .isEqualTo(expected.get(i).getKey());
      assertThat(items.get(i).get("distance").doubleValue()).isEqualTo(expected.get(i).getValue());
      assertThat(items.get(i).get("route").get("board_id").asString())
          .isEqualTo(CatalogSeed.BOARD_ID);
    }
  }

  @Test
  void similarRoutesValidateTheLimitAndTheRoute() {
    Account account = registerReady("INTERMEDIATE");
    String id = candidateIds().get(0);

    assertThat(get("/v1/routes/" + id + "/similar?limit=0", account.token()).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(get("/v1/routes/" + id + "/similar?limit=51", account.token()).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(get("/v1/routes/" + id + "/similar?limit=x", account.token()).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(get("/v1/routes/ghost-0001:40/similar", account.token()).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/v1/routes/" + id + "/similar", null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void withoutEmbeddingsSimilarIsEmptyAndV2FallsBackToTheCommunityGrade() {
    removeModelOutputs();
    Account account = registerReady("INTERMEDIATE");
    String id = candidateIds().get(0);

    JsonNode similar = tree(get("/v1/routes/" + id + "/similar", account.token()).getBody());
    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 5, UUID.randomUUID(), List.of()));

    assertThat(similar.get("model_version").isNull()).isTrue();
    assertThat(similar.get("items")).isEmpty();
    assertThat(response.get("policy_version").asString()).isEqualTo("heuristic-v2");
    assertThat(response.get("model_version").asString()).isEqualTo("none");
    for (JsonNode candidate : snapshot(response).get("candidates")) {
      assertThat(candidate.get("model_outputs").get("grade_used").doubleValue())
          .isEqualTo(candidate.get("features").get("grade_numeric").doubleValue());
    }
  }

  private void insertOutput(String publicId, String model, double grade, float[] embedding) {
    int colon = publicId.lastIndexOf(':');
    String sql =
        "INSERT INTO route_model_outputs (variant_id, model_version, predicted_difficulty,"
            + " embedding) SELECT v.variant_id, :model, :grade, "
            + (embedding == null ? "NULL" : "CAST(:embedding AS real[])")
            + " FROM route_variants v JOIN routes r ON r.route_id = v.route_id"
            + " WHERE r.board_id = :board AND r.provider_route_uuid = :uuid AND v.angle_deg = :angle"
            + " ON CONFLICT DO NOTHING";
    var statement =
        jdbc.sql(sql)
            .param("model", model)
            .param("grade", grade)
            .param("board", CatalogSeed.BOARD_ID)
            .param("uuid", publicId.substring(0, colon))
            .param("angle", Integer.parseInt(publicId.substring(colon + 1)));
    if (embedding != null) {
      StringBuilder literal = new StringBuilder("{");
      for (int j = 0; j < embedding.length; j++) {
        literal.append(j == 0 ? "" : ",").append(embedding[j]);
      }
      statement = statement.param("embedding", literal.append('}').toString());
    }
    statement.update();
  }

  /** Independent cosine distance, accumulated in double in index order like the contract says. */
  private static double cosine(float[] a, float[] b) {
    double dot = 0;
    double na = 0;
    double nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      na += (double) a[i] * a[i];
      nb += (double) b[i] * b[i];
    }
    double c = dot / (Math.sqrt(na) * Math.sqrt(nb));
    return 1.0 - Math.max(-1.0, Math.min(1.0, c));
  }

  private JsonNode record(JsonNode response) {
    UUID decisionId = UUID.fromString(response.get("decision_id").asString());
    return tree((String) decisionRow(decisionId).get("record_json"));
  }

  private JsonNode snapshot(JsonNode response) {
    String digest = record(response).get("candidate_set_digest").asString();
    return tree(new String(snapshotFile(digest), StandardCharsets.UTF_8));
  }
}
