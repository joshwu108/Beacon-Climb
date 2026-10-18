package com.beaconclimb.api.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.api.features.FeatureRecomputeService;
import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.features.StreamSnapshot;
import com.beaconclimb.api.parity.ParityReport.Detail;
import com.beaconclimb.api.parity.ParityReport.Status;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.features.FeatureAggregates;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.node.ObjectNode;

/**
 * BC-0306 oracle against Postgres, without Kafka: users write attempts through the HTTP path (the
 * hook stores the SYNC row at W = now), a STREAM row is materialised directly through {@link
 * FeatureSnapshotRepository#upsertStream} with chosen bytes, and the service's verdict, the
 * provenance values and the diff keys are asserted per case. The last test proves the oracle writes
 * nothing: row counts and the latest timestamps of every table it reads are unchanged after a run
 * over every user in the shared database.
 */
class ParityServiceIT extends AttemptsTestSupport {

  private static final Instant T = Instant.parse("2026-10-14T14:00:00Z");
  private static final String SHA256_OF_EMPTY =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @Autowired ParityService parity;
  @Autowired FeatureRecomputeService features;
  @Autowired FeatureSnapshotRepository snapshots;

  @BeforeEach
  void pinClock() {
    testClock.fix(T);
  }

  @Test
  @DisplayName("STREAM row equal to the batch at its cutoff_w: EQUAL with the row's provenance")
  void equalWhenTheStreamRowMatchesTheBatchAtItsCutoff() {
    Account account = registerWithConsent();
    attempt(account, visibleRouteIds(2).get(0), T.minus(Duration.ofHours(1)), true);
    attempt(account, visibleRouteIds(2).get(1), T.minus(Duration.ofDays(2)), false);
    FeatureSnapshot batch = features.compute(account.userId(), T);
    materialize(
        account.userId(),
        T,
        batch.featureAsOf(),
        batch.aggregates().toJson(),
        batch.provenanceChecksum(),
        3,
        41L);

    Detail detail = parity.check(account.userId());

    assertThat(detail.status()).isEqualTo(Status.EQUAL);
    assertThat(detail.userId()).isEqualTo(account.userId());
    assertThat(detail.cutoffW()).isEqualTo(T);
    assertThat(detail.syncCutoffW()).isEqualTo(T);
    assertThat(detail.streamFeatureAsOf()).isEqualTo(T.minus(Duration.ofHours(1)));
    assertThat(detail.batchFeatureAsOf()).isEqualTo(T.minus(Duration.ofHours(1)));
    assertThat(detail.streamPartition()).isEqualTo(3);
    assertThat(detail.streamOffset()).isEqualTo(41L);
    assertThat(detail.diffKeys()).isEmpty();
    assertThat(detail.checksumDiffers()).isFalse();
  }

  @Test
  @DisplayName("the batch runs at the STREAM cutoff, not at the SYNC cutoff or now")
  void batchRunsAtTheStreamCutoff() {
    Account account = registerWithConsent();
    attempt(account, visibleRouteId(), T.minus(Duration.ofDays(20)), true);
    attempt(account, visibleRouteIds(2).get(1), T.minus(Duration.ofDays(2)), true);
    Instant w = T.minus(Duration.ofDays(10));
    FeatureSnapshot batchAtW = features.compute(account.userId(), w);
    assertThat(batchAtW.aggregates().attempts90d()).isEqualTo(1);
    materialize(
        account.userId(),
        w,
        batchAtW.featureAsOf(),
        batchAtW.aggregates().toJson(),
        batchAtW.provenanceChecksum(),
        0,
        7L);

    Detail detail = parity.check(account.userId());

    assertThat(detail.status()).isEqualTo(Status.EQUAL);
    assertThat(detail.cutoffW()).isEqualTo(w);
    assertThat(detail.syncCutoffW()).isEqualTo(T);
    assertThat(detail.batchFeatureAsOf()).isEqualTo(T.minus(Duration.ofDays(20)));
  }

  @Test
  @DisplayName("a diverged STREAM row: DIFF naming the keys and whether the checksum differs")
  void diffListsTheDivergingKeysAndTheChecksum() {
    Account account = registerWithConsent();
    attempt(account, visibleRouteId(), T.minus(Duration.ofHours(1)), true);
    FeatureSnapshot batch = features.compute(account.userId(), T);
    ObjectNode altered = (ObjectNode) Jcs.parse(batch.aggregates().toJson());
    altered.put("sends_7d", 0);
    altered.put("sends_30d", 0);
    materialize(
        account.userId(),
        T,
        batch.featureAsOf(),
        Jcs.canonicalize(altered),
        batch.provenanceChecksum(),
        1,
        9L);

    Detail valuesOnly = parity.check(account.userId());

    assertThat(valuesOnly.status()).isEqualTo(Status.DIFF);
    assertThat(valuesOnly.diffKeys()).containsExactly("sends_30d", "sends_7d");
    assertThat(valuesOnly.checksumDiffers()).isFalse();

    // At another cutoff than the SYNC row (same-W mismatches take precedence otherwise).
    Instant earlier = T.minus(Duration.ofMinutes(1));
    materialize(
        account.userId(),
        earlier,
        batch.featureAsOf(),
        batch.aggregates().toJson(),
        "0".repeat(64),
        1,
        10L);
    Detail checksumOnly = parity.check(account.userId());
    assertThat(checksumOnly.status()).isEqualTo(Status.DIFF);
    assertThat(checksumOnly.cutoffW()).isEqualTo(earlier);
    assertThat(checksumOnly.syncCutoffW()).isEqualTo(T);
    assertThat(checksumOnly.diffKeys()).isEmpty();
    assertThat(checksumOnly.checksumDiffers()).isTrue();
    assertThat(checksumOnly.streamOffset()).isEqualTo(10L);
  }

  @Test
  @DisplayName("no STREAM row: NO_STREAM_ROW, skipped with nulls")
  void noStreamRowIsSkipped() {
    Account account = registerWithConsent();
    attempt(account, visibleRouteId(), T.minus(Duration.ofHours(1)), true);

    Detail detail = parity.check(account.userId());

    assertThat(detail.status()).isEqualTo(Status.NO_STREAM_ROW);
    assertThat(detail.cutoffW()).isNull();
    assertThat(detail.streamOffset()).isNull();
    assertThat(detail.diffKeys()).isEmpty();
  }

  @Test
  @DisplayName("SYNC and STREAM at the same W with different checksums: SAME_W_MISMATCH")
  void sameCutoffWithDifferentChecksumsIsAMismatch() {
    Account account = registerWithConsent();
    attempt(account, visibleRouteId(), T.minus(Duration.ofHours(1)), true);
    FeatureSnapshot batch = features.compute(account.userId(), T);
    materialize(
        account.userId(),
        T,
        batch.featureAsOf(),
        batch.aggregates().toJson(),
        batch.provenanceChecksum(),
        0,
        3L);
    assertThat(parity.check(account.userId()).status()).isEqualTo(Status.EQUAL);
    assertThat(
            jdbc.sql(
                    "UPDATE user_feature_snapshots SET provenance_checksum = :checksum"
                        + " WHERE user_id = :id AND source = 'SYNC'")
                .param("checksum", "0".repeat(64))
                .param("id", account.userId())
                .update())
        .isEqualTo(1);

    Detail detail = parity.check(account.userId());

    assertThat(detail.status()).isEqualTo(Status.SAME_W_MISMATCH);
    assertThat(detail.syncCutoffW()).isEqualTo(T);
    assertThat(detail.diffKeys()).isEmpty();
    assertThat(detail.checksumDiffers()).isFalse();
  }

  @Test
  @DisplayName("empty included set: EQUAL although the STREAM feature_as_of is the watermark")
  void emptySetComparesAggregatesAndChecksumOnly() {
    Account account = registerWithConsent();
    FeatureSnapshot batch = features.compute(account.userId(), T);
    assertThat(batch.featureAsOf()).isNull();
    materialize(account.userId(), T, T, batch.aggregates().toJson(), SHA256_OF_EMPTY, 0, 0L);

    Detail detail = parity.check(account.userId());

    assertThat(detail.status()).isEqualTo(Status.EQUAL);
    assertThat(detail.streamFeatureAsOf()).isEqualTo(T);
    assertThat(detail.batchFeatureAsOf()).isNull();
    assertThat(detail.syncCutoffW()).isNull();
  }

  @Test
  @DisplayName("run: counts, the synthetic gate on compared users only, ids never usernames")
  void runReportsCountsAndTheSyntheticGate() {
    Account real = registerWithConsent();
    attempt(real, visibleRouteId(), T.minus(Duration.ofHours(1)), true);
    FeatureSnapshot batch = features.compute(real.userId(), T);
    materialize(
        real.userId(),
        T,
        batch.featureAsOf(),
        batch.aggregates().toJson(),
        batch.provenanceChecksum(),
        0,
        5L);

    ParityReport realReport = parity.run(Optional.of(real.userId()), "deadbeef");

    assertThat(realReport.usersTotal()).isEqualTo(1);
    assertThat(realReport.compared()).isEqualTo(1);
    assertThat(realReport.count(Status.EQUAL)).isEqualTo(1);
    assertThat(realReport.syntheticOnly()).isFalse();
    assertThat(realReport.nonSyntheticCompared()).isEqualTo(1);
    assertThat(realReport.commit()).isEqualTo("deadbeef");
    assertThat(realReport.generatedAt()).isEqualTo(T);

    rename(real.userId(), "synthetic_parity_" + real.userId().toString().substring(0, 8));
    ParityReport synthetic = parity.run(Optional.of(real.userId()), "deadbeef");
    assertThat(synthetic.syntheticOnly()).isTrue();
    assertThat(synthetic.passed()).isTrue();
    assertThat(synthetic.toJson().toString())
        .contains(real.userId().toString())
        .doesNotContain("synthetic_parity_");

    // A non-synthetic user WITHOUT a STREAM row is skipped, not gated.
    Account skipped = registerWithConsent();
    ParityReport skippedReport = parity.run(Optional.of(skipped.userId()), "deadbeef");
    assertThat(skippedReport.compared()).isZero();
    assertThat(skippedReport.count(Status.NO_STREAM_ROW)).isEqualTo(1);
    assertThat(skippedReport.syntheticOnly()).isTrue();
    assertThat(skippedReport.passed()).isTrue();
  }

  @Test
  @DisplayName("the oracle never writes: counts and latest timestamps unchanged after a full run")
  void oracleNeverWrites() {
    Account account = registerWithConsent();
    attempt(account, visibleRouteId(), T.minus(Duration.ofHours(2)), true);
    FeatureSnapshot batch = features.compute(account.userId(), T);
    materialize(
        account.userId(),
        T,
        batch.featureAsOf(),
        batch.aggregates().toJson(),
        batch.provenanceChecksum(),
        0,
        11L);
    Map<String, Object> before = tableState();

    ParityReport report = parity.run(Optional.empty(), "deadbeef");

    assertThat(report.usersTotal()).isGreaterThanOrEqualTo(1);
    assertThat(report.compared()).isGreaterThanOrEqualTo(1);
    assertThat(tableState()).isEqualTo(before);
    assertThat(snapshots.find(account.userId(), FeatureAggregates.SCHEMA_VERSION))
        .get()
        .extracting(FeatureSnapshot::cutoffW)
        .isEqualTo(T);
  }

  private Map<String, Object> tableState() {
    return jdbc.sql(
            "SELECT (SELECT count(*) FROM users) AS users,"
                + " (SELECT count(*) FROM attempts) AS attempts,"
                + " (SELECT max(updated_at) FROM attempts) AS attempts_updated,"
                + " (SELECT count(*) FROM attempt_revisions) AS revisions,"
                + " (SELECT count(*) FROM attempt_event_outbox) AS outbox,"
                + " (SELECT count(*) FROM user_feature_snapshots) AS snapshots,"
                + " (SELECT max(computed_at) FROM user_feature_snapshots) AS snapshots_computed,"
                + " (SELECT count(*) FROM user_feature_snapshots WHERE source = 'STREAM')"
                + " AS stream_rows,"
                + " (SELECT count(*) FROM recommendation_decisions) AS decisions,"
                + " (SELECT count(*) FROM reconciliation_events) AS reconciliation,"
                + " (SELECT count(*) FROM route_variants) AS variants")
        .query()
        .singleRow();
  }

  private void materialize(
      UUID userId,
      Instant cutoffW,
      Instant featureAsOf,
      String aggregatesJson,
      String checksum,
      int partition,
      long offset) {
    assertThat(
            snapshots.upsertStream(
                new StreamSnapshot(
                    userId, cutoffW, featureAsOf, aggregatesJson, checksum, partition, offset, T)))
        .isTrue();
  }

  private void rename(UUID userId, String username) {
    assertThat(
            jdbc.sql("UPDATE users SET username = :username WHERE id = :id")
                .param("username", username)
                .param("id", userId)
                .update())
        .isEqualTo(1);
  }

  private String attempt(Account account, String route, Instant occurredAt, boolean sent) {
    Map<String, Object> body = attemptBody(route, occurredAt);
    body.put("sent", sent);
    ResponseEntity<String> created =
        postAttempt(account.token(), UUID.randomUUID().toString(), body);
    assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
    return tree(created.getBody()).get("attempt_id").asString();
  }
}
