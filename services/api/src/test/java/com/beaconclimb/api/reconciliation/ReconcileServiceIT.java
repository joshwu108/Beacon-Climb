package com.beaconclimb.api.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.api.features.FeatureRecomputeService;
import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.reconciliation.ReconcileService.Report;
import com.beaconclimb.api.reconciliation.ReconcileService.UserResult;
import com.beaconclimb.features.FeatureAggregates;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0309 repair job against Postgres, without Kafka: a user writes attempts through the HTTP path,
 * one of them three days old (the api stored it and its hook recomputed the SYNC row; only the
 * stream would have judged it late), an open reconciliation row is inserted for it, and the service
 * closes the row with the resolution the SYNC row's state calls for. Checksum parity with {@link
 * FeatureRecomputeService} at the recorded {@code W} is asserted both times.
 */
class ReconcileServiceIT extends AttemptsTestSupport {

  private static final Duration THREE_DAYS = Duration.ofDays(3);
  private static final String STALE_CHECKSUM = "0".repeat(64);

  @Autowired ReconcileService service;
  @Autowired ReconciliationRepository repository;
  @Autowired FeatureRecomputeService features;
  @Autowired FeatureSnapshotRepository snapshots;

  @Test
  @DisplayName(
      "the hook already included the late attempt: row closed ALREADY_CURRENT, SYNC row at W")
  void alreadyCurrentWhenTheSyncRowAlreadyHoldsTheLateAttempt() {
    Account account = registerWithConsent();
    createAttempt(account, visibleRouteId());
    ReconciliationEvent late = lateEvent(account, 0, 0);
    assertThat(repository.insert(late, Instant.now())).isTrue();
    String before = syncRow(account.userId()).provenanceChecksum();

    Report report = service.reconcileAll();

    UserResult result = resultOf(report, account.userId());
    assertThat(result.resolution()).isEqualTo(Resolution.ALREADY_CURRENT);
    assertThat(result.rows()).isEqualTo(1);
    assertThat(result.checksum()).isEqualTo(before);
    assertResolvedAndInParity(late.eventId(), account.userId(), result);
  }

  @Test
  @DisplayName("a stale SYNC row: row closed RECONCILED, SYNC row recomputed at W with parity")
  void reconciledWhenTheSyncRowWasStale() {
    Account account = registerWithConsent();
    createAttempt(account, visibleRouteId());
    ReconciliationEvent late = lateEvent(account, 1, 7);
    assertThat(repository.insert(late, Instant.now())).isTrue();
    // The live row predates the late attempt and carries another checksum (an older cutoff, so
    // the monotonic rule lets W = now replace it).
    assertThat(
            jdbc.sql(
                    "UPDATE user_feature_snapshots SET cutoff_w = cutoff_w - interval '1 hour',"
                        + " provenance_checksum = :checksum WHERE user_id = :id AND source = 'SYNC'")
                .param("checksum", STALE_CHECKSUM)
                .param("id", account.userId())
                .update())
        .isEqualTo(1);

    Report report = service.reconcileAll();

    UserResult result = resultOf(report, account.userId());
    assertThat(result.resolution()).isEqualTo(Resolution.RECONCILED);
    assertThat(result.rows()).isEqualTo(1);
    assertThat(result.checksum()).isNotEqualTo(STALE_CHECKSUM);
    assertResolvedAndInParity(late.eventId(), account.userId(), result);
    assertThat(report.anyFailed()).isFalse();
  }

  @Test
  @DisplayName("no SYNC row at all: RECONCILED, and every open row of the user closes together")
  void reconciledWhenNoSyncRowExistsAndAllRowsOfTheUserClose() {
    Account account = registerWithConsent();
    ReconciliationEvent first = lateEvent(account, 2, 3);
    ReconciliationEvent second = lateEvent(account, 3, 4);
    assertThat(repository.insert(first, Instant.now())).isTrue();
    assertThat(repository.insert(second, Instant.now())).isTrue();
    assertThat(
            jdbc.sql("DELETE FROM user_feature_snapshots WHERE user_id = :id AND source = 'SYNC'")
                .param("id", account.userId())
                .update())
        .isEqualTo(1);

    UserResult result = service.reconcileUser(account.userId());

    assertThat(result.resolution()).isEqualTo(Resolution.RECONCILED);
    assertThat(result.rows()).isEqualTo(2);
    assertResolvedAndInParity(first.eventId(), account.userId(), result);
    assertThat(repository.findByEventId(second.eventId()).orElseThrow().resolution())
        .isEqualTo(Resolution.RECONCILED);
    assertThat(repository.openRowsOf(account.userId())).isEmpty();
  }

  @Test
  @DisplayName("a run with nothing open reports zero users and touches nothing")
  void nothingOpenIsAnEmptyReport() {
    Account account = registerWithConsent();
    ReconciliationEvent late = lateEvent(account, 4, 5);
    assertThat(repository.insert(late, Instant.now())).isTrue();
    assertThat(service.reconcileUser(account.userId()).rows()).isEqualTo(1);
    FeatureSnapshot afterFirstRun = syncRow(account.userId());

    Report report = service.reconcileAll();

    assertThat(report.users()).noneMatch(u -> u.userId().equals(account.userId()));
    assertThat(ReconcileRunner.summary(new Report(List.of())))
        .isEqualTo("reconcile summary users=0 rows=0 reconciled=0 already_current=0 errors=0");
    assertThat(syncRow(account.userId())).isEqualTo(afterFirstRun);
  }

  private void assertResolvedAndInParity(UUID eventId, UUID userId, UserResult result) {
    ReconciliationRow row = repository.findByEventId(eventId).orElseThrow();
    assertThat(row.isOpen()).isFalse();
    assertThat(row.resolution()).isEqualTo(result.resolution());
    assertThat(row.resolvedCutoffW()).isEqualTo(result.cutoffW());
    assertThat(row.resolvedChecksum()).isEqualTo(result.checksum());
    FeatureSnapshot sync = syncRow(userId);
    assertThat(sync.cutoffW()).isEqualTo(result.cutoffW());
    assertThat(sync.provenanceChecksum()).isEqualTo(result.checksum());
    // Parity: the oracle at the recorded W reproduces the stored checksum and aggregates.
    FeatureSnapshot oracle = features.recompute(userId, result.cutoffW()).snapshot();
    assertThat(oracle.provenanceChecksum()).isEqualTo(sync.provenanceChecksum());
    assertThat(oracle.aggregates()).isEqualTo(sync.aggregates());
  }

  private FeatureSnapshot syncRow(UUID userId) {
    return snapshots.find(userId, FeatureAggregates.SCHEMA_VERSION).orElseThrow();
  }

  private static UserResult resultOf(Report report, UUID userId) {
    return report.users().stream().filter(u -> u.userId().equals(userId)).findFirst().orElseThrow();
  }

  /**
   * Writes a three-day-old attempt through the HTTP path (so Postgres holds it and the hook
   * recomputed the SYNC row) and builds the reconciliation event the consumer would have stored for
   * its outbox payload.
   */
  private ReconciliationEvent lateEvent(Account account, int partition, long offset) {
    Instant occurredAt = Instant.now().minus(THREE_DAYS).minusSeconds(60);
    ResponseEntity<String> created =
        postAttempt(
            account.token(),
            UUID.randomUUID().toString(),
            attemptBody(visibleRouteId(), occurredAt));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID attemptId = UUID.fromString(tree(created.getBody()).get("attempt_id").asString());
    Map<String, Object> outbox = outboxRows(attemptId).getFirst();
    String payload = outbox.get("payload_json").toString();
    JsonNode node = tree(payload);
    return new ReconciliationEvent(
        UUID.fromString(node.get("event_id").asString()),
        account.userId(),
        attemptId,
        node.get("attempt_revision").asInt(),
        node.get("event_type").asString(),
        Instant.parse(node.get("occurred_at").asString()),
        Instant.now(),
        ReconciliationEvent.REASON_LATE,
        new String(payload.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
        partition,
        offset);
  }
}
