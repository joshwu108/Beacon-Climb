package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.features.FeatureRecomputeService;
import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.features.StreamSnapshot;
import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.outbox.StreamTestSupport;
import com.beaconclimb.features.FeatureAggregates;
import com.beaconclimb.features.FeatureAggregates.BandCounts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * BC-0310 part 2 over HTTP with the stream read path enabled ({@code beacon.stream.enabled=true},
 * so the context carries the Kafka beans against {@link
 * com.beaconclimb.api.outbox.KafkaTestContainer}; the materializer is not exercised here, STREAM
 * rows are written directly through {@link FeatureSnapshotRepository#upsertStream} with chosen
 * bytes). Asserts the read rule, the response's {@code features_source} / {@code features_cutoff_w}
 * / {@code feature_as_of}, that the ranking used the chosen row, the persisted ledger columns (V11)
 * and that {@code getDecision} echoes the persisted source.
 */
class RecommendationsStreamReadIT extends StreamTestSupport {

  private static final Instant T = Instant.parse("2026-10-14T14:00:00Z");
  private static final Path DECISIONS_DIR = tempDecisionsDir();
  private static final String RECOMMENDATIONS = "/v1/me/sessions/recommendations";
  private static final String DECISIONS = "/v1/me/decisions/";
  private static final int ANGLE = 40;

  @Autowired FeatureSnapshotRepository snapshots;
  @Autowired FeatureRecomputeService features;

  @DynamicPropertySource
  static void decisionsDir(DynamicPropertyRegistry registry) {
    registry.add("beacon.decisions.dir", DECISIONS_DIR::toString);
  }

  private static Path tempDecisionsDir() {
    try {
      return Files.createTempDirectory("decisions-stream-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @BeforeEach
  void pinClock() {
    testClock.fix(T);
  }

  @Test
  @DisplayName("only a SYNC row: SYNC served, NO_STREAM_ROW persisted, getDecision echoes SYNC")
  void onlySyncRowIsServedWithNoStreamRowReason() {
    Account account = registerReady("INTERMEDIATE");
    attemptAt(account, candidateIds().get(0), T.minus(Duration.ofHours(3)));

    JsonNode response = recommendOk(account.token(), body("TECHNIQUE", 5));

    assertThat(response.get("features_source").asString()).isEqualTo("SYNC");
    assertThat(response.get("features_cutoff_w").asString()).isEqualTo("2026-10-14T14:00:00Z");
    assertThat(response.get("cold_start").asBoolean()).isFalse();
    Map<String, Object> row = decisionRow(response);
    assertThat(row.get("feature_source")).isEqualTo("SYNC");
    assertThat(row.get("feature_fallback_reason")).isEqualTo("NO_STREAM_ROW");
    assertThat(decision(account, response).get("features_source").asString()).isEqualTo("SYNC");

    // No row at all: the recompute writes the SYNC row and the reason is the same.
    Account fresh = registerReady("NOVICE");
    JsonNode cold = recommendOk(fresh.token(), body("TECHNIQUE", 3));
    assertThat(cold.get("features_source").asString()).isEqualTo("SYNC");
    assertThat(decisionRow(cold).get("feature_fallback_reason")).isEqualTo("NO_STREAM_ROW");
  }

  @Test
  @DisplayName("a fresher STREAM row is served: its provenance, its aggregates, STREAM persisted")
  void fresherStreamRowIsServedAndPersisted() {
    Account account = registerReady("INTERMEDIATE");
    // The user has no attempt at all, so a SYNC recompute would be cold; the STREAM row says
    // warm (a recent variant, a graded send), which is what the ranking must have used.
    Instant streamW = T.minus(Duration.ofMinutes(5));
    Instant streamAsOf = T.minus(Duration.ofMinutes(6));
    String recent = candidateIds().get(0);
    assertThat(snapshots.find(account.userId(), FeatureAggregates.SCHEMA_VERSION)).isEmpty();
    materialize(account.userId(), streamW, streamAsOf, warmAggregates(recent, streamAsOf), 2, 7L);

    JsonNode response = recommendOk(account.token(), body("TECHNIQUE", 5));

    assertThat(response.get("features_source").asString()).isEqualTo("STREAM");
    assertThat(response.get("features_cutoff_w").asString()).isEqualTo(streamW.toString());
    assertThat(response.get("feature_as_of").asString()).isEqualTo(streamAsOf.toString());
    assertThat(response.get("cold_start").asBoolean()).isFalse();
    for (JsonNode item : response.get("recommendations")) {
      assertThat(item.get("reason_codes").valueStream().map(JsonNode::asString))
          .doesNotContain("COLD_START_HEURISTIC");
    }
    Map<String, Object> row = decisionRow(response);
    assertThat(row.get("feature_source")).isEqualTo("STREAM");
    assertThat(row.get("feature_fallback_reason")).isNull();
    assertThat(row.get("cold_start")).isEqualTo(false);
    assertThat(row.get("feature_cutoff_w")).isNotNull();
    // No SYNC row was written on the way: the read rule did not recompute.
    assertThat(snapshots.find(account.userId(), FeatureAggregates.SCHEMA_VERSION)).isEmpty();
    JsonNode stored = decision(account, response);
    assertThat(stored.get("features_source").asString()).isEqualTo("STREAM");
    assertThat(stored.get("features_cutoff_w").asString()).isEqualTo(streamW.toString());
    assertThat(stored.get("feature_as_of").asString()).isEqualTo(streamAsOf.toString());
    assertThat(stored.get("cold_start").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("STREAM at the same cutoff as SYNC is served (ties go to STREAM)")
  void equalCutoffsServeTheStreamRow() {
    Account account = registerReady("ADVANCED");
    attemptAt(account, candidateIds().get(1), T.minus(Duration.ofHours(2)));
    FeatureSnapshot sync = snapshots.find(account.userId(), 1).orElseThrow();
    assertThat(sync.cutoffW()).isEqualTo(T);
    materialize(account.userId(), T, sync.featureAsOf(), sync.aggregates().toJson(), 0, 3L);

    JsonNode response = recommendOk(account.token(), body("STRENGTH", 4));

    assertThat(response.get("features_source").asString()).isEqualTo("STREAM");
    assertThat(response.get("features_cutoff_w").asString()).isEqualTo("2026-10-14T14:00:00Z");
    assertThat(decisionRow(response).get("feature_fallback_reason")).isNull();
  }

  @Test
  @DisplayName("STREAM older than SYNC: SYNC served, STREAM_OLDER_THAN_SYNC persisted")
  void olderStreamRowFallsBackToSync() {
    Account account = registerReady("BEGINNER");
    attemptAt(account, candidateIds().get(2), T.minus(Duration.ofHours(1)));
    Instant older = T.minus(Duration.ofDays(1));
    materialize(
        account.userId(), older, older, FeatureAggregatesFixtures.empty(older).toJson(), 1, 9L);

    JsonNode response = recommendOk(account.token(), body("ENDURANCE", 4));

    assertThat(response.get("features_source").asString()).isEqualTo("SYNC");
    assertThat(response.get("features_cutoff_w").asString()).isEqualTo("2026-10-14T14:00:00Z");
    assertThat(response.get("cold_start").asBoolean()).isFalse();
    Map<String, Object> row = decisionRow(response);
    assertThat(row.get("feature_source")).isEqualTo("SYNC");
    assertThat(row.get("feature_fallback_reason")).isEqualTo("STREAM_OLDER_THAN_SYNC");
    assertThat(decision(account, response).get("features_source").asString()).isEqualTo("SYNC");
  }

  /** Empty-set aggregates at a cutoff (what the stream emits after a full retraction). */
  private static final class FeatureAggregatesFixtures {
    static FeatureAggregates empty(Instant cutoffW) {
      return com.beaconclimb.features.FeatureComputer.compute(List.of(), cutoffW).aggregates();
    }
  }

  private static String warmAggregates(String recentVariantId, Instant lastSessionAt) {
    TreeMap<String, BandCounts> bands = new TreeMap<>();
    bands.put("INTERMEDIATE", new BandCounts(3, 2));
    return new FeatureAggregates(
            3, 3, 3, 2, 2, 2, bands, List.of(recentVariantId), lastSessionAt, 20.5, 1)
        .toJson();
  }

  private void materialize(
      UUID userId,
      Instant cutoffW,
      Instant featureAsOf,
      String aggregatesJson,
      int partition,
      long offset) {
    assertThat(
            snapshots.upsertStream(
                new StreamSnapshot(
                    userId,
                    cutoffW,
                    featureAsOf,
                    aggregatesJson,
                    features.compute(userId, cutoffW).provenanceChecksum(),
                    partition,
                    offset,
                    T)))
        .isTrue();
  }

  private Account registerReady(String band) {
    Account account = registerWithConsent();
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
    assertThat(put("/v1/me/preferences", body, account.token()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    return account;
  }

  private Map<String, Object> body(String goal, int maxRoutes) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("board_id", CatalogSeed.BOARD_ID);
    body.put("angle_deg", ANGLE);
    body.put("goal", goal);
    body.put("max_routes", maxRoutes);
    body.put("session_id", UUID.randomUUID().toString());
    body.put("exclude_route_variant_ids", List.of());
    return body;
  }

  private JsonNode recommendOk(String token, Map<String, Object> body) {
    ResponseEntity<String> response = post(RECOMMENDATIONS, json.writeValueAsString(body), token);
    assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("RecommendationResponse", response.getBody());
    return tree(response.getBody());
  }

  private JsonNode decision(Account account, JsonNode response) {
    ResponseEntity<String> stored =
        get(DECISIONS + response.get("decision_id").asString(), account.token());
    assertThat(stored.getStatusCode()).as(stored.getBody()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Decision", stored.getBody());
    return tree(stored.getBody());
  }

  private Map<String, Object> decisionRow(JsonNode response) {
    return jdbc.sql(
            "SELECT feature_source, feature_fallback_reason, feature_cutoff_w, cold_start"
                + " FROM recommendation_decisions WHERE decision_id = :id")
        .param("id", UUID.fromString(response.get("decision_id").asString()))
        .query()
        .singleRow();
  }

  private void attemptAt(Account account, String routeVariantId, Instant occurredAt) {
    ResponseEntity<String> created =
        postAttempt(
            account.token(), UUID.randomUUID().toString(), attemptBody(routeVariantId, occurredAt));
    assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
  }

  /** Public ids of the visible, graded, placed variants at the angle: the candidate pool. */
  private List<String> candidateIds() {
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
}
