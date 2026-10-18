package com.beaconclimb.stream.features;

import static com.beaconclimb.stream.features.TestEvents.USER_A;
import static com.beaconclimb.stream.features.TestEvents.USER_B;
import static com.beaconclimb.stream.features.TestEvents.VARIANT;
import static com.beaconclimb.stream.features.TestEvents.corrected;
import static com.beaconclimb.stream.features.TestEvents.id;
import static com.beaconclimb.stream.features.TestEvents.recorded;
import static com.beaconclimb.stream.features.TestEvents.retracted;
import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.features.FeatureComputer;
import com.beaconclimb.stream.AttemptEvent;
import com.beaconclimb.stream.KeyedBytes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0004 processing rules on the keyed operator, one scenario per test. */
class ClimberFeaturesFunctionTest {

  static final Instant T = Instant.parse("2026-10-14T14:00:00Z");
  static final String EMPTY_CHECKSUM = FeatureComputer.provenanceChecksum(List.of());

  @Test
  @DisplayName("duplicate delivery: the same event twice emits once and keeps one state entry")
  void duplicateDelivery() throws Exception {
    try (Harness h = Harness.open()) {
      AttemptEvent event = recorded(USER_A, id(1), T, VARIANT, true, 20.0);
      h.process(event);
      h.process(event);

      assertThat(h.emitted()).hasSize(1);
      assertThat(h.usersWithState()).isEqualTo(1);
      assertThat(h.late()).isEmpty();
    }
  }

  @Test
  @DisplayName("a lower revision after a higher one is ignored: no emit, state keeps the higher")
  void lowerRevisionAfterHigher() throws Exception {
    try (Harness h = Harness.open()) {
      h.process(corrected(USER_A, id(1), 2, T, VARIANT, false, 20.0));
      h.process(recorded(USER_A, id(1), T, VARIANT, true, 20.0));
      // A second attempt triggers a recompute that reveals which revision the state holds.
      h.process(recorded(USER_A, id(2), T, VARIANT, false, 20.0));

      List<Emitted> emitted = h.emitted();
      assertThat(emitted).hasSize(2);
      assertThat(emitted.get(1).count("attempts_7d")).isEqualTo(2);
      assertThat(emitted.get(1).count("sends_7d")).isZero();
      assertThat(emitted.get(1).checksum())
          .isEqualTo(
              FeatureComputer.provenanceChecksum(
                  List.of(
                      new com.beaconclimb.features.AttemptFeatureRow(
                          id(1), 2, VARIANT, T, false, 20.0),
                      new com.beaconclimb.features.AttemptFeatureRow(
                          id(2), 1, VARIANT, T, false, 20.0))));
    }
  }

  @Test
  @DisplayName("out of order: r2 before r1 applies r2 once, r1 is ignored")
  void outOfOrderRevisions() throws Exception {
    try (Harness h = Harness.open()) {
      h.process(corrected(USER_A, id(1), 2, T, VARIANT, false, 25.0));
      Emitted afterR2 = h.emitted().get(0);
      h.process(recorded(USER_A, id(1), T, VARIANT, true, 25.0));

      assertThat(h.emitted()).hasSize(1);
      assertThat(afterR2.count("sends_90d")).isZero();
      assertThat(afterR2.aggregates().get("max_sent_grade_numeric_90d").isNull()).isTrue();
      assertThat(h.usersWithState()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("retraction: the tombstone drops the attempt from aggregates and the checksum")
  void retraction() throws Exception {
    try (Harness h = Harness.open()) {
      h.watermark(T.toEpochMilli());
      h.process(recorded(USER_A, id(1), T.minusSeconds(60), VARIANT, true, 20.0));
      h.process(recorded(USER_A, id(2), T, VARIANT, true, 21.0));
      h.process(retracted(USER_A, id(1), 2, T.minusSeconds(60)));

      List<Emitted> emitted = h.emitted();
      assertThat(emitted).hasSize(3);
      assertThat(emitted.get(1).count("attempts_90d")).isEqualTo(2);
      assertThat(emitted.get(2).count("attempts_90d")).isEqualTo(1);
      assertThat(emitted.get(2).count("sends_90d")).isEqualTo(1);
      assertThat(emitted.get(2).checksum()).isNotEqualTo(emitted.get(1).checksum());
      assertThat(emitted.get(2).checksum())
          .isEqualTo(
              FeatureComputer.provenanceChecksum(
                  List.of(
                      new com.beaconclimb.features.AttemptFeatureRow(
                          id(2), 1, VARIANT, T, true, 21.0))));
      // The tombstone stays in state so a replayed lower revision cannot resurrect the attempt.
      h.process(recorded(USER_A, id(1), T.minusSeconds(60), VARIANT, true, 20.0));
      assertThat(h.emitted()).hasSize(3);
      assertThat(h.usersWithState()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName(
      "correction at an unchanged watermark: a new record with the same source_watermark and a different checksum")
  void correctionAtUnchangedWatermark() throws Exception {
    try (Harness h = Harness.open()) {
      h.watermark(T.toEpochMilli());
      h.process(recorded(USER_A, id(1), T.minusSeconds(30), VARIANT, true, 20.0));
      h.process(corrected(USER_A, id(1), 2, T.minusSeconds(30), VARIANT, false, 20.0));

      List<Emitted> emitted = h.emitted();
      assertThat(emitted).hasSize(2);
      assertThat(emitted.get(0).sourceWatermark()).isEqualTo(T);
      assertThat(emitted.get(1).sourceWatermark()).isEqualTo(T);
      assertThat(emitted.get(0).count("sends_7d")).isEqualTo(1);
      assertThat(emitted.get(1).count("sends_7d")).isZero();
      assertThat(emitted.get(1).checksum()).isNotEqualTo(emitted.get(0).checksum());
      assertThat(emitted.get(1).featureAsOf()).isEqualTo(T.minusSeconds(30));
    }
  }

  @Test
  @DisplayName(
      "2 h behind the watermark is inside the lateness window: applied, W stays the watermark")
  void twoHoursLateInsideWindow() throws Exception {
    try (Harness h = Harness.open()) {
      h.watermark(T.toEpochMilli());
      Instant at = T.minus(Duration.ofHours(2));
      h.process(recorded(USER_A, id(1), at, VARIANT, true, 20.0));

      List<Emitted> emitted = h.emitted();
      assertThat(emitted).hasSize(1);
      assertThat(emitted.get(0).sourceWatermark()).isEqualTo(T);
      assertThat(emitted.get(0).featureAsOf()).isEqualTo(at);
      assertThat(emitted.get(0).count("attempts_7d")).isEqualTo(1);
      assertThat(h.late()).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "3 days behind the watermark: side output with the original bytes and headers, state unchanged, no emit")
  void threeDaysLate() throws Exception {
    try (Harness h = Harness.open()) {
      h.watermark(T.toEpochMilli());
      AttemptEvent late = recorded(USER_A, id(1), T.minus(Duration.ofDays(3)), VARIANT, true, 20.0);
      h.process(late);

      assertThat(h.emitted()).isEmpty();
      assertThat(h.usersWithState()).isZero();
      List<KeyedBytes> lateRecords = h.late();
      assertThat(lateRecords).hasSize(1);
      assertThat(lateRecords.get(0).key).isEqualTo(USER_A);
      assertThat(lateRecords.get(0).value).isEqualTo(late.raw());
      assertThat(lateRecords.get(0).headers)
          .containsEntry(ClimberFeaturesFunction.HEADER_REASON, ClimberFeaturesFunction.REASON_LATE)
          .containsEntry(ClimberFeaturesFunction.HEADER_WATERMARK, T.toString());
    }
  }

  @Test
  @DisplayName("exactly 24 h behind the watermark is still applied; 24 h + 1 ms is late")
  void latenessBoundary() throws Exception {
    try (Harness h = Harness.open()) {
      h.watermark(T.toEpochMilli());
      h.process(recorded(USER_A, id(1), T.minus(Duration.ofHours(24)), VARIANT, true, 20.0));
      h.process(
          recorded(
              USER_A, id(2), T.minus(Duration.ofHours(24)).minusMillis(1), VARIANT, true, 20.0));

      assertThat(h.emitted()).hasSize(1);
      assertThat(h.late()).hasSize(1);
    }
  }

  @Test
  @DisplayName(
      "empty after retraction: zeros, the empty-set checksum, last_session_at null and feature_as_of = source_watermark")
  void emptyAfterRetraction() throws Exception {
    try (Harness h = Harness.open()) {
      h.process(recorded(USER_A, id(1), T, VARIANT, true, 20.0));
      h.process(retracted(USER_A, id(1), 2, T));

      List<Emitted> emitted = h.emitted();
      assertThat(emitted).hasSize(2);
      Emitted empty = emitted.get(1);
      assertThat(empty.checksum()).isEqualTo(EMPTY_CHECKSUM);
      assertThat(empty.count("attempts_90d")).isZero();
      assertThat(empty.aggregates().get("last_session_at").isNull()).isTrue();
      assertThat(empty.sourceWatermark()).isEqualTo(T);
      assertThat(empty.featureAsOf()).isEqualTo(empty.sourceWatermark());
      assertThat(empty.aggregatesText())
          .isEqualTo(FeatureComputer.compute(List.of(), T).aggregates().toJson());
    }
  }

  @Test
  @DisplayName(
      "W rule: no watermark -> W = occurred_at; ahead of the watermark -> W = occurred_at; behind -> W = watermark")
  void cutoffRule() throws Exception {
    try (Harness h = Harness.open()) {
      h.process(recorded(USER_A, id(1), T, VARIANT, true, 20.0));
      h.watermark(T.toEpochMilli());
      h.process(recorded(USER_A, id(2), T.plus(Duration.ofHours(1)), VARIANT, true, 20.0));
      h.process(recorded(USER_A, id(3), T.minus(Duration.ofHours(1)), VARIANT, true, 20.0));

      List<Emitted> emitted = h.emitted();
      assertThat(emitted).hasSize(3);
      assertThat(emitted.get(0).sourceWatermark()).isEqualTo(T);
      assertThat(emitted.get(1).sourceWatermark()).isEqualTo(T.plus(Duration.ofHours(1)));
      assertThat(emitted.get(2).sourceWatermark()).isEqualTo(T);
    }
  }

  @Test
  @DisplayName(
      "parity subtlety: a row ahead of W stays in state and is excluded until W reaches it")
  void rowsAheadOfCutoffAreExcluded() throws Exception {
    try (Harness h = Harness.open()) {
      h.watermark(T.toEpochMilli());
      Instant ahead = T.plus(Duration.ofHours(5));
      h.process(recorded(USER_A, id(1), ahead, VARIANT, true, 20.0));
      h.process(recorded(USER_A, id(2), T.minus(Duration.ofHours(1)), VARIANT, false, 20.0));
      h.watermark(ahead.toEpochMilli());
      h.process(recorded(USER_A, id(3), T, VARIANT, false, 20.0));

      List<Emitted> emitted = h.emitted();
      assertThat(emitted).hasSize(3);
      // First event: W = its own occurred_at, so it is included.
      assertThat(emitted.get(0).sourceWatermark()).isEqualTo(ahead);
      assertThat(emitted.get(0).count("attempts_7d")).isEqualTo(1);
      // Second event: W = watermark T; the row at T+5h is ahead of W and excluded.
      assertThat(emitted.get(1).sourceWatermark()).isEqualTo(T);
      assertThat(emitted.get(1).count("attempts_7d")).isEqualTo(1);
      assertThat(emitted.get(1).featureAsOf()).isEqualTo(T.minus(Duration.ofHours(1)));
      // Third event after the watermark passed T+5h: all three rows included.
      assertThat(emitted.get(2).sourceWatermark()).isEqualTo(ahead);
      assertThat(emitted.get(2).count("attempts_7d")).isEqualTo(3);
      assertThat(emitted.get(2).featureAsOf()).isEqualTo(ahead);
      assertThat(h.usersWithState()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName(
      "restore from a checkpoint: state survives, the first event sees no watermark so W = occurred_at")
  void restoreFromSnapshot() throws Exception {
    OperatorSubtaskState snapshot;
    try (Harness h = Harness.open()) {
      h.watermark(T.toEpochMilli());
      h.process(recorded(USER_A, id(1), T.minusSeconds(60), VARIANT, true, 20.0));
      h.process(recorded(USER_B, id(2), T.minusSeconds(60), VARIANT, true, null));
      h.process(retracted(USER_B, id(2), 2, T.minusSeconds(60)));
      snapshot = h.snapshot(1L);
    }
    try (Harness h = Harness.open(snapshot)) {
      assertThat(h.usersWithState()).isEqualTo(2);
      h.process(recorded(USER_A, id(3), T, VARIANT, false, 20.0));
      h.process(recorded(USER_B, id(2), T.minusSeconds(60), VARIANT, true, null));

      List<Emitted> emitted = h.emitted();
      assertThat(emitted).hasSize(1);
      assertThat(emitted.get(0).userId()).isEqualTo(USER_A);
      assertThat(emitted.get(0).sourceWatermark()).isEqualTo(T);
      assertThat(emitted.get(0).count("attempts_7d")).isEqualTo(2);
      assertThat(emitted.get(0).count("sends_7d")).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("an event without a user_id key is ignored")
  void emptyUserIdIgnored() throws Exception {
    try (Harness h = Harness.open()) {
      AttemptEvent valid = recorded(USER_A, id(1), T, VARIANT, true, 20.0);
      AttemptEvent unkeyed =
          new AttemptEvent(
              valid.eventId(),
              valid.eventType(),
              "",
              valid.attemptId(),
              valid.attemptRevision(),
              valid.routeVariantId(),
              valid.occurredAtMillis(),
              valid.sent(),
              valid.status(),
              valid.gradeNumeric(),
              valid.raw());
      h.process(unkeyed);

      assertThat(h.emitted()).isEmpty();
      assertThat(h.late()).isEmpty();
      assertThat(h.usersWithState()).isZero();
    }
  }

  @Test
  @DisplayName("users are isolated: one user's events never change another's aggregates")
  void usersAreIsolated() throws Exception {
    try (Harness h = Harness.open()) {
      h.process(recorded(USER_A, id(1), T, VARIANT, true, 20.0));
      h.process(recorded(USER_B, id(2), T, VARIANT, true, 20.0));
      h.process(recorded(USER_B, id(3), T, VARIANT, true, 20.0));

      List<Emitted> emitted = h.emitted();
      assertThat(emitted).extracting(Emitted::userId).containsExactly(USER_A, USER_B, USER_B);
      assertThat(emitted.get(2).count("attempts_7d")).isEqualTo(2);
      assertThat(emitted.get(0).count("attempts_7d")).isEqualTo(1);
    }
  }
}
