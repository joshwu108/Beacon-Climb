package com.beaconclimb.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.features.FeatureAggregates;
import com.beaconclimb.features.GoldenFixture;
import com.beaconclimb.features.GoldenFixture.Attempt;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BC-0304 follow-up / BC-0306: the api's SQL path ({@link FeatureRecomputeService}: Postgres rows
 * joined to the variant's grade, folded through the shared {@code com.beaconclimb.features}
 * computer) reproduces every committed golden fixture of {@code services/features} byte for byte.
 * The same files are replayed through the Flink operator in {@code :stream}, so the batch oracle
 * and the stream path are held to one fixture set.
 *
 * <p>Each fixture's input is inserted as the rows the attempts table would hold after the HTTP
 * flow: the highest revision per {@code attempt_id} (the first listed among equal revisions) at its
 * status, so the SQL predicate ({@code status = 'ACTIVE'}, {@code occurred_at <= W}) and the
 * computer do the filtering. The fixture gives {@code grade_numeric} per row while the api reads
 * the variant's current grade, so one synthetic variant is created per distinct (public id, grade)
 * pair of the fixture and the attempts row keeps the fixture's public id text. Fixture attempt ids
 * repeat across files and are a primary key, so each case runs under its own synthetic user and
 * removes its rows again when it is done.
 */
class GoldenFixturesApiTest extends AttemptsTestSupport {

  static final Path DIRECTORY =
      Path.of("..", "features", "src", "test", "resources", "golden", "climber-features-v1")
          .toAbsolutePath()
          .normalize();
  static final int MINIMUM_CASES = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired FeatureRecomputeService features;
  @Autowired FeatureSnapshotRepository snapshots;

  static Stream<GoldenFixture> fixtures() {
    return GoldenFixture.readAll(DIRECTORY).stream();
  }

  @Test
  @DisplayName("the shared fixture set is present and every case has an expected block")
  void committedSet() {
    List<GoldenFixture> all = GoldenFixture.readAll(DIRECTORY);

    assertThat(all).hasSizeGreaterThanOrEqualTo(MINIMUM_CASES);
    assertThat(all).extracting(GoldenFixture::name).doesNotHaveDuplicates();
    assertThat(all).allSatisfy(fixture -> assertThat(fixture.expected()).isNotNull());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  @DisplayName("SQL rows + shared computer reproduce the aggregates text, checksum and as-of")
  void sqlPathReproducesFixture(GoldenFixture fixture) {
    UUID userId = insertSyntheticUser(fixture.name());
    try {
      for (Attempt attempt : currentRevisions(fixture)) {
        insertAttempt(userId, fixture.name(), attempt);
      }

      FeatureRecomputeService.Outcome outcome = features.recompute(userId, fixture.cutoffW());

      FeatureSnapshot snapshot = outcome.snapshot();
      assertThat(snapshot.aggregates().toJson())
          .as("aggregates of %s", fixture.name())
          .isEqualTo(fixture.expected().aggregatesJson());
      assertThat(snapshot.provenanceChecksum())
          .as("checksum of %s", fixture.name())
          .isEqualTo(fixture.expected().provenanceChecksum());
      assertThat(snapshot.featureAsOf())
          .as("feature_as_of of %s", fixture.name())
          .isEqualTo(fixture.expected().featureAsOf());
      assertThat(outcome.stored()).isTrue();
      // The stored jsonb re-normalises whitespace; its canonical text is the fixture's bytes.
      assertThat(Jcs.canonicalize(storedAggregatesText(userId)))
          .isEqualTo(fixture.expected().aggregatesJson());
      assertThat(
              snapshots
                  .find(userId, FeatureAggregates.SCHEMA_VERSION)
                  .orElseThrow()
                  .aggregates()
                  .toJson())
          .isEqualTo(fixture.expected().aggregatesJson());
    } finally {
      removeSyntheticUser(userId);
    }
  }

  /** The row set the attempts table holds: one row per attempt_id at its current revision. */
  static List<Attempt> currentRevisions(GoldenFixture fixture) {
    Map<UUID, Attempt> latest = new LinkedHashMap<>();
    for (Attempt attempt : fixture.attempts()) {
      latest.merge(
          attempt.attemptId(),
          attempt,
          (kept, next) -> next.revision() > kept.revision() ? next : kept);
    }
    return List.copyOf(latest.values());
  }

  private UUID insertSyntheticUser(String fixtureName) {
    UUID userId = UUID.randomUUID();
    byte[] bytes = new byte[4];
    RANDOM.nextBytes(bytes);
    jdbc.sql("INSERT INTO users (id, username, password_hash) VALUES (:id, :username, :hash)")
        .param("id", userId)
        .param("username", "synthetic_golden_" + HexFormat.of().formatHex(bytes))
        .param("hash", "argon2id$placeholder-" + fixtureName)
        .update();
    return userId;
  }

  private void removeSyntheticUser(UUID userId) {
    jdbc.sql("DELETE FROM attempts WHERE user_id = :id").param("id", userId).update();
    jdbc.sql("DELETE FROM user_feature_snapshots WHERE user_id = :id").param("id", userId).update();
    jdbc.sql("DELETE FROM users WHERE id = :id").param("id", userId).update();
    jdbc.sql(
            "DELETE FROM route_variants WHERE route_id IN (SELECT route_id FROM routes"
                + " WHERE board_id = :board AND provider_route_uuid LIKE 'zz-g-%')")
        .param("board", CatalogSeed.BOARD_ID)
        .update();
    jdbc.sql("DELETE FROM routes WHERE board_id = :board AND provider_route_uuid LIKE 'zz-g-%'")
        .param("board", CatalogSeed.BOARD_ID)
        .update();
  }

  private void insertAttempt(UUID userId, String fixtureName, Attempt attempt) {
    UUID variantId = variantFor(fixtureName, attempt.routeVariantId(), attempt.gradeNumeric());
    jdbc.sql(
            "INSERT INTO attempts (attempt_id, user_id, variant_id, route_variant_public_id,"
                + " occurred_at, received_at, attempts_count, sent, revision, status,"
                + " submission_idempotency_key, request_fingerprint, response_body_json,"
                + " updated_at) VALUES (:attempt_id, :user_id, :variant_id, :public_id,"
                + " :occurred_at, :occurred_at, 1, :sent, :revision, :status, :key, :fingerprint,"
                + " '{}'::jsonb, :occurred_at)")
        .param("attempt_id", attempt.attemptId())
        .param("user_id", userId)
        .param("variant_id", variantId)
        .param("public_id", attempt.routeVariantId())
        .param("occurred_at", attempt.occurredAt().atOffset(java.time.ZoneOffset.UTC))
        .param("sent", attempt.sent())
        .param("revision", attempt.revision())
        .param("status", attempt.status())
        .param("key", attempt.attemptId().toString())
        .param("fingerprint", "0".repeat(64))
        .update();
  }

  /**
   * One ACTIVE demo-board variant per (fixture, public id, grade); idempotent. The route's provider
   * uuid encodes all three so a fixture row with another grade for the same public id gets its own
   * variant, which is what the SQL join reads the grade from.
   */
  private UUID variantFor(String fixtureName, String publicId, Double grade) {
    String providerUuid = providerUuid(fixtureName, publicId, grade);
    UUID routeId = UUID.nameUUIDFromBytes(("route:" + providerUuid).getBytes());
    UUID variantId = UUID.nameUUIDFromBytes(("variant:" + providerUuid).getBytes());
    UUID provenance =
        jdbc.sql("SELECT provenance_id FROM routes WHERE board_id = :board LIMIT 1")
            .param("board", CatalogSeed.BOARD_ID)
            .query(UUID.class)
            .single();
    jdbc.sql(
            "INSERT INTO routes (route_id, board_id, provider_route_uuid, name, tags_json,"
                + " provenance_id) VALUES (:id, :board, :uuid, 'Golden', '[]', :prov)"
                + " ON CONFLICT (board_id, provider_route_uuid) DO NOTHING")
        .param("id", routeId)
        .param("board", CatalogSeed.BOARD_ID)
        .param("uuid", providerUuid)
        .param("prov", provenance)
        .update();
    jdbc.sql(
            "INSERT INTO route_variants (variant_id, route_id, angle_deg, grade_numeric,"
                + " grade_observation_count, validation_status) VALUES (:vid, :rid, 40, :grade,"
                + " 0, 'ACTIVE') ON CONFLICT (route_id, angle_deg) DO NOTHING")
        .param("vid", variantId)
        .param("rid", routeId)
        .param("grade", grade == null ? null : java.math.BigDecimal.valueOf(grade))
        .update();
    return variantId;
  }

  /**
   * A valid public-id prefix (no colon, under 64 characters) that sorts after the demo routes, so
   * other classes' {@code visibleRouteIds} never pick a golden route even before cleanup.
   */
  static String providerUuid(String fixtureName, String publicId, Double grade) {
    String gradeToken = grade == null ? "null" : String.valueOf(grade).replace('.', 'p');
    return "zz-g-"
        + fixtureName.substring(0, 2)
        + "-"
        + publicId.replace(':', '_')
        + "-"
        + gradeToken;
  }

  private String storedAggregatesText(UUID userId) {
    return jdbc.sql(
            "SELECT aggregates_json::text FROM user_feature_snapshots WHERE user_id = :id"
                + " AND feature_schema_version = 1 AND source = 'SYNC'")
        .param("id", userId)
        .query(String.class)
        .single();
  }
}
