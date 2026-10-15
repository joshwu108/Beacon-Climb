package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.ApiApplication;
import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.identity.ContractSchemas;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Harness for the recommendation and decision endpoints: the attempts harness (seeded demo board,
 * pinnable clock, consent helpers, raised write limit) plus a snapshot store rooted in a temporary
 * directory and helpers for preferences and recommendation calls.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {ApiApplication.class, AttemptsTestSupport.TestClockConfig.class},
    properties = {"beacon.http.write-limit-per-minute=1000", "beacon.http.public-burst=1000"})
public abstract class RecommendationsTestSupport extends AttemptsTestSupport {

  protected static final String RECOMMENDATIONS = "/v1/me/sessions/recommendations";
  protected static final String DECISIONS = "/v1/me/decisions/";
  protected static final int ANGLE = 40;
  protected static final Path DECISIONS_DIR = tempDecisionsDir();
  protected static final Path EXAMPLES =
      Path.of("..", "..", "contracts", "examples").toAbsolutePath().normalize();

  @Autowired protected SnapshotStore store;
  @Autowired protected DecisionRecordSchema recordSchema;

  @DynamicPropertySource
  static void decisionsDir(DynamicPropertyRegistry registry) {
    registry.add("beacon.decisions.dir", DECISIONS_DIR::toString);
  }

  private static Path tempDecisionsDir() {
    try {
      return Files.createTempDirectory("decisions-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A registered user with consent and preferences on the demo board at the given band. */
  protected Account registerReady(String band) {
    Account account = registerWithConsent();
    setPreferences(account.token(), band);
    return account;
  }

  protected void setPreferences(String token, String band) {
    String body =
        json.writeValueAsString(
            Map.of(
                "board_id",
                CatalogSeed.BOARD_ID,
                "angle_deg",
                ANGLE,
                "initial_grade_band",
                band,
                "goals",
                List.of("TECHNIQUE")));
    assertThat(put("/v1/me/preferences", body, token).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  protected Map<String, Object> recommendationBody(
      String goal, int maxRoutes, UUID sessionId, List<String> exclude) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("board_id", CatalogSeed.BOARD_ID);
    body.put("angle_deg", ANGLE);
    body.put("goal", goal);
    body.put("max_routes", maxRoutes);
    body.put("session_id", sessionId.toString());
    body.put("exclude_route_variant_ids", exclude);
    return body;
  }

  protected ResponseEntity<String> recommend(String token, Map<String, Object> body) {
    return post(RECOMMENDATIONS, json.writeValueAsString(body), token);
  }

  /** A 200 that conforms to {@code RecommendationResponse}, as a tree. */
  protected JsonNode recommendOk(String token, Map<String, Object> body) {
    ResponseEntity<String> response = recommend(token, body);
    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("RecommendationResponse", response.getBody());
    return tree(response.getBody());
  }

  protected String attemptAt(Account account, String routeVariantId, Instant occurredAt) {
    ResponseEntity<String> created =
        postAttempt(
            account.token(), UUID.randomUUID().toString(), attemptBody(routeVariantId, occurredAt));
    assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
    return tree(created.getBody()).get("attempt_id").asString();
  }

  /** Public ids of the visible, graded, placed variants at the angle: the candidate pool. */
  protected List<String> candidateIds() {
    return jdbc.sql(
            "SELECT r.provider_route_uuid || ':' || v.angle_deg FROM route_variants v"
                + " JOIN routes r ON r.route_id = v.route_id WHERE v.validation_status = 'ACTIVE'"
                + " AND r.board_id = :board AND v.angle_deg = :angle"
                + " AND v.grade_numeric IS NOT NULL"
                + " AND (r.active_from IS NULL OR r.active_from <= now())"
                + " AND (r.active_to IS NULL OR r.active_to > now())"
                + " AND EXISTS (SELECT 1 FROM route_placements p WHERE p.variant_id = v.variant_id)"
                + " ORDER BY r.provider_route_uuid")
        .param("board", CatalogSeed.BOARD_ID)
        .param("angle", ANGLE)
        .query(String.class)
        .list();
  }

  protected List<String> servedIds(JsonNode response) {
    return response
        .get("recommendations")
        .valueStream()
        .map(item -> item.get("route_variant_id").asString())
        .toList();
  }

  protected List<Long> servedScoreBits(JsonNode response) {
    return response
        .get("recommendations")
        .valueStream()
        .map(item -> Double.doubleToRawLongBits(item.get("rank_score").doubleValue()))
        .toList();
  }

  protected Map<String, Object> decisionRow(UUID decisionId) {
    return jdbc.sql(
            "SELECT decision_id, user_id, session_id, policy_version, policy_code_digest,"
                + " model_version, feature_snapshot_user_id, feature_schema_version,"
                + " feature_as_of, feature_cutoff_w, candidate_set_digest, candidate_snapshot_uri,"
                + " rng_seed, environment_digest, request_json::text AS request_json,"
                + " record_json::text AS record_json, cold_start, fallback_used, erased"
                + " FROM recommendation_decisions WHERE decision_id = :id")
        .param("id", decisionId)
        .query()
        .singleRow();
  }

  protected List<Map<String, Object>> exposureRows(UUID decisionId) {
    return jdbc.sql(
            "SELECT position, route_variant_id, rank_score, reason_codes_json::text AS reasons"
                + " FROM recommendation_exposures WHERE decision_id = :id ORDER BY position")
        .param("id", decisionId)
        .query()
        .listOfRows();
  }

  protected byte[] snapshotFile(String digest) {
    try {
      return Files.readAllBytes(
          DECISIONS_DIR.resolve(digest.substring(0, 2)).resolve(digest + ".json"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  protected JsonNode fixture(String operation, String name) {
    try {
      return json.readTree(
          Files.readString(EXAMPLES.resolve(operation).resolve(name), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
