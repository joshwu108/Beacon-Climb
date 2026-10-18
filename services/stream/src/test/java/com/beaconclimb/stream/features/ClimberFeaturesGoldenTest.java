package com.beaconclimb.stream.features;

import static com.beaconclimb.stream.features.TestEvents.USER_A;
import static com.beaconclimb.stream.features.TestEvents.USER_B;
import static com.beaconclimb.stream.features.TestEvents.USER_C;
import static com.beaconclimb.stream.features.TestEvents.corrected;
import static com.beaconclimb.stream.features.TestEvents.id;
import static com.beaconclimb.stream.features.TestEvents.recorded;
import static com.beaconclimb.stream.features.TestEvents.retracted;
import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.features.AttemptFeatureRow;
import com.beaconclimb.features.FeatureComputer;
import com.beaconclimb.features.GoldenFixture;
import com.beaconclimb.features.GoldenFixture.Attempt;
import com.beaconclimb.stream.AttemptEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parity of the operator with the shared computer: the emitted {@code aggregates} bytes and
 * checksum equal {@code FeatureComputer.compute(deduplicated set, W)} at the record's own {@code
 * source_watermark}, and every record validates against climber-features.v1.schema.json.
 */
class ClimberFeaturesGoldenTest {

  static final Instant T = Instant.parse("2026-10-14T14:00:00Z");
  static final Path FIXTURES =
      Path.of("..", "features", "src", "test", "resources", "golden", "climber-features-v1");

  @Test
  @DisplayName(
      "several users with mixed revisions, duplicates and retractions: every emitted record equals the computer over the de-duplicated set at its W")
  void multiUserParity() throws Exception {
    List<AttemptEvent> events = new ArrayList<>();
    events.add(recorded(USER_A, id(1), T.minus(Duration.ofDays(10)), "r-1:40", true, 15.0));
    events.add(recorded(USER_B, id(2), T.minus(Duration.ofDays(1)), "r-2:40", false, null));
    events.add(corrected(USER_A, id(1), 2, T.minus(Duration.ofDays(10)), "r-1:40", false, 15.0));
    events.add(recorded(USER_A, id(1), T.minus(Duration.ofDays(10)), "r-1:40", true, 15.0));
    events.add(recorded(USER_C, id(3), T.minus(Duration.ofHours(2)), "r-3:40", true, 27.5));
    events.add(recorded(USER_A, id(4), T.minus(Duration.ofHours(1)), "r-4:40", true, 19.0));
    events.add(recorded(USER_B, id(5), T.minus(Duration.ofHours(4)), "r-5:40", true, 22.0));
    events.add(retracted(USER_B, id(2), 2, T.minus(Duration.ofDays(1))));
    events.add(recorded(USER_B, id(5), T.minus(Duration.ofHours(4)), "r-5:40", true, 22.0));
    events.add(corrected(USER_C, id(3), 3, T.minus(Duration.ofHours(2)), "r-3:40", false, 27.5));
    events.add(corrected(USER_C, id(3), 2, T.minus(Duration.ofHours(2)), "r-3:40", true, 27.5));
    events.add(recorded(USER_A, id(6), T.plus(Duration.ofHours(3)), "r-6:40", true, 30.0));
    events.add(recorded(USER_A, id(7), T, "r-7:40", false, 30.0));

    try (Harness h = Harness.open()) {
      int i = 0;
      for (AttemptEvent event : events) {
        if (i == 4) {
          h.watermark(T.minus(Duration.ofHours(12)).toEpochMilli());
        }
        if (i == 9) {
          h.watermark(T.toEpochMilli());
        }
        h.process(event);
        i++;
      }

      List<Emitted> emitted = h.emitted();
      assertThat(h.late()).isEmpty();
      // 13 events, 3 ignored (a replayed r1 after r2, a duplicate, a stale r2 after r3).
      assertThat(emitted).hasSize(10);
      Map<String, List<AttemptEvent>> byUser = new LinkedHashMap<>();
      for (AttemptEvent event : events) {
        byUser.computeIfAbsent(event.userId(), k -> new ArrayList<>()).add(event);
      }
      for (String user : byUser.keySet()) {
        Emitted last =
            emitted.stream().filter(e -> e.userId().equals(user)).reduce((a, b) -> b).orElseThrow();
        FeatureComputer.Result expected =
            FeatureComputer.compute(deduplicated(byUser.get(user)), last.sourceWatermark());
        assertThat(last.aggregatesText()).as(user).isEqualTo(expected.aggregates().toJson());
        assertThat(last.checksum()).as(user).isEqualTo(expected.checksum());
      }
      Emitted lastA =
          emitted.stream().filter(e -> e.userId().equals(USER_A)).reduce((a, b) -> b).orElseThrow();
      // User A's last record is at W = T (the watermark): the row at T+3h is ahead and excluded.
      assertThat(lastA.sourceWatermark()).isEqualTo(T);
      assertThat(lastA.count("attempts_7d")).isEqualTo(2);
      assertThat(lastA.count("attempts_30d")).isEqualTo(3);
    }
  }

  static Stream<GoldenFixture> fixtures() {
    return GoldenFixture.readAll(FIXTURES).stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  @DisplayName(
      "golden fixture replayed as events (ascending occurred_at, no watermark): the last record equals the computer at its W, and the fixture's expected text when W = cutoff_w")
  void fixtureReplay(GoldenFixture fixture) throws Exception {
    List<Attempt> ascending =
        fixture.attempts().stream().sorted(Comparator.comparing(Attempt::occurredAt)).toList();
    try (Harness h = Harness.open()) {
      for (Attempt attempt : ascending) {
        h.process(toEvent(attempt));
      }
      List<Emitted> emitted = h.emitted();
      assertThat(h.late()).isEmpty();
      if (ascending.isEmpty()) {
        assertThat(emitted).isEmpty();
        return;
      }
      Emitted last = emitted.get(emitted.size() - 1);
      FeatureComputer.Result atW =
          FeatureComputer.compute(fixture.activeRows(), last.sourceWatermark());
      assertThat(last.aggregatesText()).isEqualTo(atW.aggregates().toJson());
      assertThat(last.checksum()).isEqualTo(atW.checksum());
      if (last.sourceWatermark().equals(fixture.cutoffW())) {
        assertThat(last.aggregatesText()).isEqualTo(fixture.expected().aggregatesJson());
        assertThat(last.checksum()).isEqualTo(fixture.expected().provenanceChecksum());
      }
    }
  }

  @Test
  @DisplayName(
      "the fixtures whose last row sits at cutoff_w are compared against their expected text")
  void fixturesComparedAgainstExpectedText() {
    List<String> atCutoff =
        fixtures()
            .filter(
                fixture ->
                    fixture.attempts().stream()
                        .map(Attempt::occurredAt)
                        .max(Comparator.naturalOrder())
                        .map(max -> max.equals(fixture.cutoffW()))
                        .orElse(false))
            .map(GoldenFixture::name)
            .toList();

    assertThat(atCutoff).hasSizeGreaterThanOrEqualTo(4);
  }

  static AttemptEvent toEvent(Attempt attempt) {
    String type =
        attempt.isRetracted()
            ? AttemptEvent.ATTEMPT_RETRACTED
            : attempt.revision() == 1
                ? AttemptEvent.ATTEMPT_RECORDED
                : AttemptEvent.ATTEMPT_CORRECTED;
    return TestEvents.event(
        USER_A,
        attempt.attemptId(),
        attempt.revision(),
        type,
        attempt.status(),
        attempt.occurredAt(),
        attempt.routeVariantId(),
        attempt.sent(),
        attempt.gradeNumeric());
  }

  /** Highest revision per attempt_id over the delivered events, tombstones dropped. */
  static List<AttemptFeatureRow> deduplicated(List<AttemptEvent> events) {
    Map<UUID, AttemptEvent> latest = new LinkedHashMap<>();
    for (AttemptEvent event : events) {
      latest.merge(
          UUID.fromString(event.attemptId()),
          event,
          (kept, next) -> next.attemptRevision() > kept.attemptRevision() ? next : kept);
    }
    List<AttemptFeatureRow> rows = new ArrayList<>();
    for (Map.Entry<UUID, AttemptEvent> entry : latest.entrySet()) {
      AttemptEvent event = entry.getValue();
      if (!AttemptEvent.ATTEMPT_RETRACTED.equals(event.eventType())) {
        rows.add(
            new AttemptFeatureRow(
                entry.getKey(),
                event.attemptRevision(),
                event.routeVariantId(),
                Instant.ofEpochMilli(event.occurredAtMillis()),
                event.sent(),
                event.gradeNumeric()));
      }
    }
    return rows;
  }
}
