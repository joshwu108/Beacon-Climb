package com.beaconclimb.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.features.FeatureAggregates.BandCounts;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** contracts/features/climber-features.v1.md sections 2 to 6 on hand-built rows. */
class FeatureComputerTest {

  private static final Instant W = Instant.parse("2026-10-14T14:00:00Z");
  private static final String SHA256_OF_EMPTY =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @Test
  void emptyInputGivesZerosNullsAndTheChecksumOfTheEmptyString() {
    FeatureComputer.Result result = FeatureComputer.compute(List.of(), W);

    FeatureAggregates a = result.aggregates();
    assertThat(a)
        .isEqualTo(
            new FeatureAggregates(
                0, 0, 0, 0, 0, 0, Collections.emptySortedMap(), List.of(), null, null, 0));
    assertThat(result.featureAsOf()).isNull();
    assertThat(result.checksum()).isEqualTo(SHA256_OF_EMPTY);
    assertThat(a.toJson())
        .isEqualTo(
            "{\"attempts_30d\":0,\"attempts_7d\":0,\"attempts_90d\":0,\"distinct_sessions_30d\":0,"
                + "\"last_session_at\":null,\"max_sent_grade_numeric_90d\":null,"
                + "\"recent_variant_ids\":[],\"send_rate_by_grade_band_90d\":{},\"sends_30d\":0,"
                + "\"sends_7d\":0,\"sends_90d\":0}");
  }

  @Test
  void windowsAreLowerExclusiveUpperInclusiveOnExactDays() {
    AttemptFeatureRow atW = row(W, true, 20.0);
    AttemptFeatureRow edge7 = row(W.minus(Duration.ofDays(7)), true, 20.0);
    AttemptFeatureRow inside7 = row(W.minus(Duration.ofDays(7)).plusMillis(1), false, 20.0);
    AttemptFeatureRow edge30 = row(W.minus(Duration.ofDays(30)), true, 20.0);
    AttemptFeatureRow edge90 = row(W.minus(Duration.ofDays(90)), true, 20.0);
    AttemptFeatureRow inside90 = row(W.minus(Duration.ofDays(90)).plusMillis(1), true, 20.0);
    AttemptFeatureRow afterW = row(W.plusMillis(1), true, 20.0);

    FeatureAggregates a =
        FeatureComputer.compute(List.of(atW, edge7, inside7, edge30, edge90, inside90, afterW), W)
            .aggregates();

    assertThat(a.attempts7d()).isEqualTo(2);
    assertThat(a.sends7d()).isEqualTo(1);
    assertThat(a.attempts30d()).isEqualTo(3);
    assertThat(a.attempts90d()).isEqualTo(5);
    assertThat(a.sends90d()).isEqualTo(4);
    assertThat(a.lastSessionAt()).isEqualTo(W);
  }

  @Test
  void sessionsSplitOnGapsStrictlyLongerThanThreeHours() {
    Instant t = W.minus(Duration.ofDays(2));
    List<AttemptFeatureRow> rows =
        List.of(
            row(t, false, 20.0),
            row(t.plus(Duration.ofHours(1)), false, 20.0),
            row(t.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(59)), false, 20.0),
            row(t.plus(Duration.ofHours(6)), false, 20.0));

    assertThat(FeatureComputer.compute(rows, W).aggregates().distinctSessions30d()).isEqualTo(2);
    assertThat(
            FeatureComputer.sessions(
                List.of(row(t, false, 20.0), row(t.plus(Duration.ofHours(3)), false, 20.0))))
        .isEqualTo(1);
    assertThat(
            FeatureComputer.sessions(
                List.of(
                    row(t, false, 20.0),
                    row(t.plus(Duration.ofHours(3)).plusMillis(1), false, 20.0))))
        .isEqualTo(2);
  }

  @Test
  void bandsBucketOnFixedEdgesAndUngradedVariantsGetTheirOwnKey() {
    List<AttemptFeatureRow> rows =
        List.of(
            row(W, true, 13.99),
            row(W, false, 14.0),
            row(W, true, 17.99),
            row(W, true, 18.0),
            row(W, false, 21.99),
            row(W, true, 22.0),
            row(W, true, 25.99),
            row(W, false, 26.0),
            row(W, true, null),
            row(W, false, null));

    FeatureAggregates a = FeatureComputer.compute(rows, W).aggregates();

    assertThat(a.sendRateByGradeBand90d())
        .containsExactly(
            Map.entry("ADVANCED", new BandCounts(2, 2)),
            Map.entry("BEGINNER", new BandCounts(2, 1)),
            Map.entry("EXPERT", new BandCounts(1, 0)),
            Map.entry("INTERMEDIATE", new BandCounts(2, 1)),
            Map.entry("NOVICE", new BandCounts(1, 1)),
            Map.entry("UNGRADED", new BandCounts(2, 1)));
    assertThat(a.maxSentGradeNumeric90d()).isEqualTo(25.99);
  }

  @Test
  void maxSentGradeIgnoresUnsentAndUngradedRows() {
    List<AttemptFeatureRow> rows =
        List.of(row(W, false, 30.0), row(W, true, null), row(W, true, 12.5));

    assertThat(FeatureComputer.compute(rows, W).aggregates().maxSentGradeNumeric90d())
        .isEqualTo(12.5);
    assertThat(
            FeatureComputer.compute(List.of(row(W, false, 30.0)), W)
                .aggregates()
                .maxSentGradeNumeric90d())
        .isNull();
  }

  @Test
  void recentVariantIdsAreDistinctNewestFirstAndCappedAtTwenty() {
    List<AttemptFeatureRow> rows = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      rows.add(row(W.minus(Duration.ofHours(i)), false, 20.0, "route-" + (i % 25) + ":40"));
    }
    Collections.shuffle(rows, new Random(7));

    List<String> recent = FeatureComputer.compute(rows, W).aggregates().recentVariantIds();

    assertThat(recent).hasSize(20).doesNotHaveDuplicates();
    assertThat(recent.get(0)).isEqualTo("route-0:40");
    assertThat(recent.get(19)).isEqualTo("route-19:40");
  }

  @Test
  void resultIsIndependentOfRowOrderAndRowsAfterWAreIgnored() {
    List<AttemptFeatureRow> rows = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      rows.add(row(W.minus(Duration.ofDays(i * 5)), i % 3 == 0, 15.0 + i));
    }
    rows.add(row(W.plusSeconds(1), true, 40.0));
    List<AttemptFeatureRow> shuffled = new ArrayList<>(rows);
    Collections.shuffle(shuffled, new Random(42));

    FeatureComputer.Result ordered = FeatureComputer.compute(rows, W);
    FeatureComputer.Result unordered = FeatureComputer.compute(shuffled, W);

    assertThat(unordered.aggregates().toJson()).isEqualTo(ordered.aggregates().toJson());
    assertThat(unordered.checksum()).isEqualTo(ordered.checksum());
    assertThat(ordered.featureAsOf()).isEqualTo(W);
    assertThat(ordered.aggregates().maxSentGradeNumeric90d()).isNotEqualTo(40.0);
  }

  @Test
  void checksumIsSha256OfSortedIdRevisionPairs() {
    UUID a = UUID.fromString("018f0000-0000-7000-8000-00000000000b");
    UUID b = UUID.fromString("018f0000-0000-7000-8000-00000000000a");
    List<AttemptFeatureRow> rows =
        List.of(
            new AttemptFeatureRow(a, 2, "r:40", W, true, 20.0),
            new AttemptFeatureRow(b, 1, "r:40", W, true, 20.0));

    String expected =
        Jcs.sha256Hex((b + ":1," + a + ":2").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(FeatureComputer.provenanceChecksum(rows)).isEqualTo(expected);
  }

  @Test
  void jsonRoundTripsThroughFromJson() {
    List<AttemptFeatureRow> rows =
        List.of(row(W.minusSeconds(1), true, 20.5), row(W.minus(Duration.ofDays(1)), false, null));
    FeatureAggregates original = FeatureComputer.compute(rows, W).aggregates();

    FeatureAggregates parsed = FeatureAggregates.fromJson(Jcs.parse(original.toJson()));

    assertThat(parsed).isEqualTo(original);
    assertThat(parsed.toJson()).isEqualTo(original.toJson());
  }

  /** BC-0124 item 9: an identical duplicate row changes nothing. */
  @Test
  void identicalDuplicateRowsCountOnce() {
    AttemptFeatureRow r = row(W.minusSeconds(60), true, 20.0);

    FeatureComputer.Result duplicated = FeatureComputer.compute(List.of(r, r), W);
    FeatureComputer.Result single = FeatureComputer.compute(List.of(r), W);

    assertThat(duplicated).isEqualTo(single);
    assertThat(duplicated.aggregates().attempts7d()).isEqualTo(1);
    assertThat(duplicated.aggregates().sends7d()).isEqualTo(1);
    assertThat(duplicated.checksum()).isEqualTo(FeatureComputer.provenanceChecksum(List.of(r)));
  }

  /** BC-0124 item 9: only the highest revision of an attempt counts, whatever the row order. */
  @Test
  void lowerRevisionOfTheSameAttemptIsIgnored() {
    UUID id = TestIds.uuid7();
    AttemptFeatureRow rev1 =
        new AttemptFeatureRow(id, 1, "route:40", W.minusSeconds(60), true, 20.0);
    AttemptFeatureRow rev2 =
        new AttemptFeatureRow(id, 2, "route:40", W.minusSeconds(60), false, 20.0);

    FeatureComputer.Result newestFirst = FeatureComputer.compute(List.of(rev2, rev1), W);
    FeatureComputer.Result oldestFirst = FeatureComputer.compute(List.of(rev1, rev2), W);
    FeatureComputer.Result onlyRev2 = FeatureComputer.compute(List.of(rev2), W);

    assertThat(newestFirst).isEqualTo(onlyRev2);
    assertThat(oldestFirst).isEqualTo(onlyRev2);
    assertThat(newestFirst.aggregates().attempts7d()).isEqualTo(1);
    assertThat(newestFirst.aggregates().sends7d()).isZero();
    assertThat(newestFirst.aggregates().sends90d()).isZero();
    assertThat(newestFirst.checksum())
        .isEqualTo(Jcs.sha256Hex((id + ":2").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static AttemptFeatureRow row(Instant at, boolean sent, Double grade) {
    return row(at, sent, grade, "route:40");
  }

  private static AttemptFeatureRow row(Instant at, boolean sent, Double grade, String variant) {
    return new AttemptFeatureRow(TestIds.uuid7(), 1, variant, at, sent, grade);
  }
}
