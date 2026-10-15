package com.beaconclimb.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.features.FeatureAggregates.BandCounts;
import com.beaconclimb.api.platform.json.Jcs;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BC-0113 acceptance against the real attempts tables (contracts/features/climber-features.v1.md):
 * identical bytes and checksum on repeat, deterministic change on correction and retraction, the
 * explicit cutoff, the empty user, session and band rules, the attempts hook and conformance of the
 * payload to the decision record's user_features.
 */
class FeatureRecomputeIT extends AttemptsTestSupport {

  private static final Instant T = Instant.parse("2026-10-14T14:00:00Z");
  private static final String SHA256_OF_EMPTY =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final String UNGRADED_ROUTE = "features-ungraded";

  @Autowired FeatureRecomputeService features;
  @Autowired FeatureSnapshotRepository snapshots;

  @BeforeEach
  void pinClock() {
    testClock.fix(T);
  }

  @Test
  void recomputeTwiceGivesIdenticalBytesAndChecksum() {
    Account account = registerWithConsent();
    List<String> routes = visibleRouteIds(3);
    String a = attempt(account, routes.get(0), T.minus(Duration.ofDays(1)), true);
    String b = attempt(account, routes.get(1), T.minus(Duration.ofDays(2)), false);
    String c = attempt(account, routes.get(2), T.minus(Duration.ofDays(40)), true);

    FeatureSnapshot first = features.recompute(account.userId(), T).snapshot();
    String firstStored = storedJson(account.userId());
    FeatureSnapshot second = features.recompute(account.userId(), T).snapshot();

    assertThat(second.aggregates().toJson()).isEqualTo(first.aggregates().toJson());
    assertThat(second.provenanceChecksum()).isEqualTo(first.provenanceChecksum());
    assertThat(storedJson(account.userId())).isEqualTo(firstStored);
    assertThat(first.provenanceChecksum()).isEqualTo(checksumOf(a + ":1", b + ":1", c + ":1"));
    FeatureAggregates agg = first.aggregates();
    assertThat(agg.attempts7d()).isEqualTo(2);
    assertThat(agg.attempts30d()).isEqualTo(2);
    assertThat(agg.attempts90d()).isEqualTo(3);
    assertThat(agg.sends7d()).isEqualTo(1);
    assertThat(agg.sends90d()).isEqualTo(2);
    assertThat(agg.recentVariantIds()).containsExactly(routes.get(0), routes.get(1), routes.get(2));
    assertThat(agg.distinctSessions30d()).isEqualTo(2);
    assertThat(first.featureAsOf()).isEqualTo(T.minus(Duration.ofDays(1)));
    assertThat(agg.lastSessionAt()).isEqualTo(first.featureAsOf());
    assertThat(first.cutoffW()).isEqualTo(T);
    assertThat(first.source()).isEqualTo(FeatureSource.SYNC);
    assertThat(snapshots.find(account.userId(), FeatureAggregates.SCHEMA_VERSION)).contains(first);
  }

  @Test
  void correctionChangesSendsAndChecksumDeterministically() {
    Account account = registerWithConsent();
    String route = visibleRouteId();
    String id = attempt(account, route, T.minus(Duration.ofHours(2)), true);
    FeatureSnapshot before = features.recompute(account.userId(), T).snapshot();

    assertThat(patchAttempt(account.token(), id, "\"1\"", "{\"sent\":false}").getStatusCode())
        .isEqualTo(HttpStatus.OK);
    FeatureSnapshot after = features.recompute(account.userId(), T).snapshot();
    FeatureSnapshot again = features.recompute(account.userId(), T).snapshot();

    assertThat(before.aggregates().sends7d()).isEqualTo(1);
    assertThat(after.aggregates().sends7d()).isZero();
    assertThat(after.aggregates().sends30d()).isZero();
    assertThat(after.aggregates().sends90d()).isZero();
    assertThat(after.aggregates().attempts7d()).isEqualTo(1);
    assertThat(before.provenanceChecksum()).isEqualTo(checksumOf(id + ":1"));
    assertThat(after.provenanceChecksum()).isEqualTo(checksumOf(id + ":2"));
    assertThat(again.aggregates().toJson()).isEqualTo(after.aggregates().toJson());
    assertThat(again.provenanceChecksum()).isEqualTo(after.provenanceChecksum());
  }

  @Test
  void retractedAttemptIsExcludedAndChecksumChanges() {
    Account account = registerWithConsent();
    List<String> routes = visibleRouteIds(2);
    String kept = attempt(account, routes.get(0), T.minus(Duration.ofHours(1)), true);
    String gone = attempt(account, routes.get(1), T.minus(Duration.ofHours(5)), true);
    FeatureSnapshot before = features.recompute(account.userId(), T).snapshot();

    assertThat(patchAttempt(account.token(), gone, "\"1\"", "{\"retract\":true}").getStatusCode())
        .isEqualTo(HttpStatus.OK);
    FeatureSnapshot after = features.recompute(account.userId(), T).snapshot();

    assertThat(before.aggregates().attempts7d()).isEqualTo(2);
    assertThat(before.aggregates().distinctSessions30d()).isEqualTo(2);
    assertThat(after.aggregates().attempts7d()).isEqualTo(1);
    assertThat(after.aggregates().sends7d()).isEqualTo(1);
    assertThat(after.aggregates().distinctSessions30d()).isEqualTo(1);
    assertThat(after.aggregates().recentVariantIds()).containsExactly(routes.get(0));
    assertThat(after.provenanceChecksum())
        .isNotEqualTo(before.provenanceChecksum())
        .isEqualTo(checksumOf(kept + ":1"));
  }

  @Test
  void cutoffEarlierThanSomeAttemptsExcludesThem() {
    Account account = registerWithConsent();
    List<String> routes = visibleRouteIds(2);
    String old = attempt(account, routes.get(0), T.minus(Duration.ofDays(20)), true);
    attempt(account, routes.get(1), T.minus(Duration.ofDays(2)), true);
    Instant w = T.minus(Duration.ofDays(10));

    FeatureSnapshot snapshot = features.recompute(account.userId(), w).snapshot();

    assertThat(snapshot.cutoffW()).isEqualTo(w);
    assertThat(snapshot.featureAsOf()).isEqualTo(T.minus(Duration.ofDays(20))).isBefore(w);
    assertThat(snapshot.aggregates().attempts90d()).isEqualTo(1);
    assertThat(snapshot.aggregates().attempts7d()).isZero();
    assertThat(snapshot.aggregates().recentVariantIds()).containsExactly(routes.get(0));
    assertThat(snapshot.provenanceChecksum()).isEqualTo(checksumOf(old + ":1"));
  }

  @Test
  void emptyUserHasZerosNullsAndTheChecksumOfTheEmptyString() {
    Account account = registerWithConsent();

    FeatureSnapshot snapshot = features.recompute(account.userId(), T).snapshot();

    assertThat(snapshot.featureAsOf()).isNull();
    assertThat(snapshot.provenanceChecksum()).isEqualTo(SHA256_OF_EMPTY);
    FeatureAggregates agg = snapshot.aggregates();
    assertThat(agg.attempts90d()).isZero();
    assertThat(agg.sends90d()).isZero();
    assertThat(agg.distinctSessions30d()).isZero();
    assertThat(agg.recentVariantIds()).isEmpty();
    assertThat(agg.sendRateByGradeBand90d()).isEmpty();
    assertThat(agg.lastSessionAt()).isNull();
    assertThat(agg.maxSentGradeNumeric90d()).isNull();
    assertThat(snapshots.find(account.userId(), FeatureAggregates.SCHEMA_VERSION))
        .contains(snapshot);
  }

  @Test
  void sessionsSplitOnGapsLongerThanThreeHours() {
    Account account = registerWithConsent();
    String route = visibleRouteId();
    Instant t = T.minus(Duration.ofDays(1));
    attempt(account, route, t, false);
    attempt(account, route, t.plus(Duration.ofHours(1)), false);
    attempt(account, route, t.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(59)), false);
    attempt(account, route, t.plus(Duration.ofHours(6)), true);

    FeatureAggregates agg = features.recompute(account.userId(), T).snapshot().aggregates();

    assertThat(agg.distinctSessions30d()).isEqualTo(2);
    assertThat(agg.attempts30d()).isEqualTo(4);
    assertThat(agg.recentVariantIds()).containsExactly(route);
  }

  @Test
  void bandsFollowTheVariantGradeAndUngradedVariantsAreKeyedSeparately() {
    Account account = registerWithConsent();
    String graded = visibleRouteId();
    double grade = gradeOf(graded);
    String ungraded = seedUngradedVariant();
    attempt(account, graded, T.minus(Duration.ofHours(1)), true);
    attempt(account, graded, T.minus(Duration.ofHours(2)), false);
    attempt(account, ungraded, T.minus(Duration.ofHours(3)), true);

    FeatureAggregates agg = features.recompute(account.userId(), T).snapshot().aggregates();

    assertThat(agg.sendRateByGradeBand90d())
        .containsOnlyKeys(GradeBandBuckets.keyFor(grade), GradeBandBuckets.UNGRADED)
        .containsEntry(GradeBandBuckets.keyFor(grade), new BandCounts(2, 1))
        .containsEntry(GradeBandBuckets.UNGRADED, new BandCounts(1, 1));
    assertThat(agg.maxSentGradeNumeric90d()).isEqualTo(grade);
  }

  @Test
  void attemptWritesRecomputeTheSnapshotAtTheClockAfterCommit() {
    Account account = registerWithConsent();
    assertThat(snapshots.find(account.userId(), FeatureAggregates.SCHEMA_VERSION)).isEmpty();

    String id = attempt(account, visibleRouteId(), T.minus(Duration.ofMinutes(30)), true);
    FeatureSnapshot afterCreate =
        snapshots.find(account.userId(), FeatureAggregates.SCHEMA_VERSION).orElseThrow();
    assertThat(afterCreate.cutoffW()).isEqualTo(T);
    assertThat(afterCreate.aggregates().attempts7d()).isEqualTo(1);
    assertThat(afterCreate.provenanceChecksum()).isEqualTo(checksumOf(id + ":1"));

    assertThat(patchAttempt(account.token(), id, "\"1\"", "{\"retract\":true}").getStatusCode())
        .isEqualTo(HttpStatus.OK);
    FeatureSnapshot afterRetract =
        snapshots.find(account.userId(), FeatureAggregates.SCHEMA_VERSION).orElseThrow();
    assertThat(afterRetract.aggregates().attempts7d()).isZero();
    assertThat(afterRetract.provenanceChecksum()).isEqualTo(SHA256_OF_EMPTY);
    assertThat(afterRetract.featureAsOf()).isNull();
  }

  @Test
  void aggregatesConformToTheDecisionRecordUserFeatures() {
    Account account = registerWithConsent();
    List<String> routes = visibleRouteIds(2);
    attempt(account, routes.get(0), T.minus(Duration.ofHours(1)), true);
    attempt(account, routes.get(1), T.minus(Duration.ofDays(3)), false);

    String json = features.recompute(account.userId(), T).snapshot().aggregates().toJson();
    String empty =
        features.recompute(registerWithConsent().userId(), T).snapshot().aggregates().toJson();

    assertThat(UserFeaturesSchema.validate(json)).as(json).isEmpty();
    assertThat(UserFeaturesSchema.validate(empty)).as(empty).isEmpty();
    assertThat(Jcs.parse(json).propertyNames())
        .containsExactlyInAnyOrderElementsOf(UserFeaturesSchema.aggregateKeys())
        .containsExactlyInAnyOrderElementsOf(FeatureAggregates.KEYS);
  }

  private String attempt(Account account, String route, Instant occurredAt, boolean sent) {
    Map<String, Object> body = attemptBody(route, occurredAt);
    body.put("sent", sent);
    ResponseEntity<String> created =
        postAttempt(account.token(), UUID.randomUUID().toString(), body);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return tree(created.getBody()).get("attempt_id").asString();
  }

  private static String checksumOf(String... pairs) {
    List<String> sorted = new ArrayList<>(List.of(pairs));
    sorted.sort(String::compareTo);
    return Jcs.sha256Hex(String.join(",", sorted).getBytes(StandardCharsets.UTF_8));
  }

  private String storedJson(UUID userId) {
    return jdbc.sql(
            "SELECT aggregates_json::text FROM user_feature_snapshots WHERE user_id = :id"
                + " AND feature_schema_version = 1")
        .param("id", userId)
        .query(String.class)
        .single();
  }

  private double gradeOf(String publicId) {
    String[] parts = publicId.split(":");
    return jdbc.sql(
            "SELECT v.grade_numeric FROM route_variants v JOIN routes r ON r.route_id = v.route_id"
                + " WHERE r.provider_route_uuid = :uuid AND v.angle_deg = :angle")
        .param("uuid", parts[0])
        .param("angle", Integer.parseInt(parts[1]))
        .query(Double.class)
        .single();
  }

  /** An ACTIVE, visible demo-board variant without a grade; idempotent across tests. */
  private String seedUngradedVariant() {
    UUID provenance =
        jdbc.sql("SELECT provenance_id FROM routes WHERE board_id = :board LIMIT 1")
            .param("board", CatalogSeed.BOARD_ID)
            .query(UUID.class)
            .single();
    jdbc.sql(
            "INSERT INTO routes (route_id, board_id, provider_route_uuid, name, tags_json,"
                + " provenance_id) VALUES (:id, :board, :uuid, 'Ungraded', '[]', :prov)"
                + " ON CONFLICT (board_id, provider_route_uuid) DO NOTHING")
        .param("id", UUID.nameUUIDFromBytes(("route:" + UNGRADED_ROUTE).getBytes()))
        .param("board", CatalogSeed.BOARD_ID)
        .param("uuid", UNGRADED_ROUTE)
        .param("prov", provenance)
        .update();
    jdbc.sql(
            "INSERT INTO route_variants (variant_id, route_id, angle_deg, grade_numeric,"
                + " grade_observation_count, validation_status) SELECT :vid, route_id, 40, NULL, 0,"
                + " 'ACTIVE' FROM routes WHERE board_id = :board AND provider_route_uuid = :uuid"
                + " ON CONFLICT (route_id, angle_deg) DO NOTHING")
        .param("vid", UUID.nameUUIDFromBytes(("variant:" + UNGRADED_ROUTE).getBytes()))
        .param("board", CatalogSeed.BOARD_ID)
        .param("uuid", UNGRADED_ROUTE)
        .update();
    return UNGRADED_ROUTE + ":40";
  }
}
