package com.beaconclimb.features;

import com.beaconclimb.features.GoldenFixture.Attempt;
import com.beaconclimb.features.GoldenFixture.Expected;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the golden fixtures ({@code ./gradlew :features:generateGoldenFixtures}): every case
 * below, with {@code expected} filled in by running the shared {@link FeatureComputer} over {@link
 * GoldenFixture#activeRows()}. Ids are the numbered v7 ids of {@link TestIds} so the files are
 * reproducible; the api-side follow-up replays the same files against its own copy of the computer.
 */
public final class GoldenFixtureGenerator {

  static final Instant W = Instant.parse("2026-10-14T14:00:00Z");
  static final String VARIANT = "synthetic-route-001:40";
  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private GoldenFixtureGenerator() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      throw new IllegalArgumentException("usage: GoldenFixtureGenerator <output-directory>");
    }
    Path directory = Path.of(args[0]);
    Files.createDirectories(directory);
    for (GoldenFixture fixture : cases()) {
      Path file = directory.resolve(fixture.name() + GoldenFixture.FILE_SUFFIX);
      Files.writeString(file, toJson(computed(fixture)), StandardCharsets.UTF_8);
      System.out.println("wrote " + file);
    }
  }

  /** The fixture with {@code expected} produced by the computer. */
  static GoldenFixture computed(GoldenFixture fixture) {
    FeatureComputer.Result result =
        FeatureComputer.compute(fixture.activeRows(), fixture.cutoffW());
    return fixture.withExpected(
        new Expected(result.featureAsOf(), result.checksum(), result.aggregates().toJson()));
  }

  static List<GoldenFixture> cases() {
    List<GoldenFixture> cases = new ArrayList<>();
    cases.add(fixture("01-empty", "no attempts: zeros, nulls, sha256 of the empty string"));
    cases.add(
        fixture(
            "02-single-sent-attempt",
            "one sent attempt at W with grade 20 (INTERMEDIATE)",
            active(1, 1, VARIANT, W, true, 20.0)));
    cases.add(
        fixture(
            "03-window-edge-7d",
            "rows at exactly W-7d (outside the 7d window, inside 30d) and W-7d+1ms (inside 7d)",
            active(1, 1, VARIANT, W.minus(Duration.ofDays(7)), true, 20.0),
            active(2, 1, VARIANT, W.minus(Duration.ofDays(7)).plusMillis(1), false, 20.0),
            active(3, 1, VARIANT, W, true, 20.0)));
    cases.add(
        fixture(
            "04-window-edge-30d",
            "rows at exactly W-30d (outside 30d, inside 90d) and W-30d+1ms (inside 30d)",
            active(1, 1, VARIANT, W.minus(Duration.ofDays(30)), true, 20.0),
            active(2, 1, VARIANT, W.minus(Duration.ofDays(30)).plusMillis(1), true, 20.0)));
    cases.add(
        fixture(
            "05-window-edge-90d",
            "rows at exactly W-90d (outside every window, still in recent_variant_ids and the"
                + " checksum) and W-90d+1ms (inside 90d)",
            active(1, 1, "synthetic-route-002:40", W.minus(Duration.ofDays(90)), true, 20.0),
            active(2, 1, VARIANT, W.minus(Duration.ofDays(90)).plusMillis(1), true, 20.0)));
    Instant t = W.minus(Duration.ofDays(2));
    cases.add(
        fixture(
            "06-sessions-exactly-3h-apart",
            "two rows exactly 3h apart stay in one session",
            active(1, 1, VARIANT, t, false, 20.0),
            active(2, 1, VARIANT, t.plus(Duration.ofHours(3)), false, 20.0)));
    cases.add(
        fixture(
            "07-sessions-3h-plus-1ms-apart",
            "two rows 3h+1ms apart open a second session; t, t+1h, t+2h59m, t+6h give 2",
            active(1, 1, VARIANT, t, false, 20.0),
            active(2, 1, VARIANT, t.plus(Duration.ofHours(1)), false, 20.0),
            active(
                3,
                1,
                VARIANT,
                t.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(59)),
                false,
                20.0),
            active(4, 1, VARIANT, t.plus(Duration.ofHours(6)), true, 20.0),
            active(5, 1, VARIANT, t.plus(Duration.ofHours(9)).plusMillis(1), true, 20.0)));
    cases.add(
        fixture(
            "08-ties-by-attempt-id",
            "three rows with the same occurred_at listed in ascending id order:"
                + " recent_variant_ids is newest first with ties by attempt_id DESC",
            active(1, 1, "synthetic-route-001:40", W.minusSeconds(60), true, 20.0),
            active(2, 1, "synthetic-route-002:40", W.minusSeconds(60), true, 20.0),
            active(3, 1, "synthetic-route-003:40", W.minusSeconds(60), true, 20.0)));
    cases.add(
        fixture(
            "09-retracted-excluded",
            "a RETRACTED current revision contributes nothing (not even to the checksum); a"
                + " retraction listed before the superseded ACTIVE revision still wins",
            active(1, 1, VARIANT, W.minusSeconds(10), true, 20.0),
            retracted(2, 2, VARIANT, W.minusSeconds(20)),
            retracted(3, 3, VARIANT, W.minusSeconds(30)),
            active(3, 2, VARIANT, W.minusSeconds(30), true, 20.0)));
    cases.add(
        fixture(
            "10-ungraded-band",
            "null grade_numeric rows fall into the UNGRADED band and never set max_sent_grade",
            active(1, 1, VARIANT, W.minusSeconds(10), true, null),
            active(2, 1, VARIANT, W.minusSeconds(20), false, null),
            active(3, 1, VARIANT, W.minusSeconds(30), true, 13.5)));
    cases.add(recentVariantsTruncation());
    cases.add(
        fixture(
            "12-fractional-second-timestamps",
            "millisecond timestamps are formatted with the shortest round-tripping fraction",
            active(1, 1, VARIANT, Instant.parse("2026-10-14T13:59:59.250Z"), true, 20.0),
            active(2, 1, VARIANT, Instant.parse("2026-10-14T13:00:00.007Z"), false, 20.0),
            active(3, 1, VARIANT, Instant.parse("2026-10-14T12:30:00.100Z"), true, 20.5)));
    cases.add(
        fixture(
            "13-max-sent-grade-with-nulls",
            "max_sent_grade_numeric_90d ignores unsent rows, null grades and rows outside 90d",
            active(1, 1, VARIANT, W.minusSeconds(10), false, 30.0),
            active(2, 1, VARIANT, W.minusSeconds(20), true, null),
            active(3, 1, VARIANT, W.minusSeconds(30), true, 12.5),
            active(4, 1, VARIANT, W.minusSeconds(40), true, 25.75),
            active(5, 1, VARIANT, W.minus(Duration.ofDays(91)), true, 40.0)));
    cases.add(
        fixture(
            "14-revision-dedupe",
            "the same attempt_id at revisions 1, 3 and 2 listed out of order: only revision 3"
                + " counts, an identical duplicate counts once",
            active(1, 1, VARIANT, W.minusSeconds(60), true, 20.0),
            active(1, 3, VARIANT, W.minusSeconds(60), false, 20.0),
            active(1, 2, VARIANT, W.minusSeconds(60), true, 20.0),
            active(2, 1, VARIANT, W.minusSeconds(90), true, 18.0),
            active(2, 1, VARIANT, W.minusSeconds(90), true, 18.0)));
    cases.add(
        fixture(
            "15-rows-after-w-excluded",
            "rows with occurred_at after W (by 1 ms and by a day) are not part of A(u, W)",
            active(1, 1, VARIANT, W, true, 20.0),
            active(2, 1, "synthetic-route-002:40", W.plusMillis(1), true, 40.0),
            active(3, 1, "synthetic-route-003:40", W.plus(Duration.ofDays(1)), true, 40.0)));
    cases.add(
        fixture(
            "16-band-edges",
            "grade_numeric at 13.99, 14, 17.99, 18, 21.99, 22, 25.99, 26 lands on the fixed edges",
            active(1, 1, VARIANT, W, true, 13.99),
            active(2, 1, VARIANT, W, false, 14.0),
            active(3, 1, VARIANT, W, true, 17.99),
            active(4, 1, VARIANT, W, true, 18.0),
            active(5, 1, VARIANT, W, false, 21.99),
            active(6, 1, VARIANT, W, true, 22.0),
            active(7, 1, VARIANT, W, true, 25.99),
            active(8, 1, VARIANT, W, false, 26.0)));
    return cases;
  }

  private static GoldenFixture recentVariantsTruncation() {
    List<Attempt> attempts = new ArrayList<>();
    // 30 rows over 25 distinct variants, one per hour back from W; variant k repeats at k+25.
    for (int i = 0; i < 30; i++) {
      attempts.add(
          active(
              i + 1,
              1,
              "synthetic-route-" + String.format("%03d", i % 25) + ":40",
              W.minus(Duration.ofHours(i)),
              i % 3 == 0,
              15.0 + (i % 12)));
    }
    return new GoldenFixture(
        "11-recent-variants-truncation",
        "30 rows over 25 distinct variants: recent_variant_ids holds the first 20 distinct"
            + " values newest first",
        W,
        attempts,
        null);
  }

  private static GoldenFixture fixture(String name, String description, Attempt... attempts) {
    return new GoldenFixture(name, description, W, List.of(attempts), null);
  }

  private static Attempt active(
      long id, int revision, String variant, Instant at, boolean sent, Double grade) {
    return new Attempt(TestIds.numbered(id), revision, variant, at, sent, grade, Attempt.ACTIVE);
  }

  private static Attempt retracted(long id, int revision, String variant, Instant at) {
    return new Attempt(TestIds.numbered(id), revision, variant, at, false, null, Attempt.RETRACTED);
  }

  static String toJson(GoldenFixture fixture) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.put("name", fixture.name());
    root.put("description", fixture.description());
    root.put("cutoff_w", Timestamps.format(fixture.cutoffW()));
    ArrayNode attempts = root.putArray("attempts");
    for (Attempt attempt : fixture.attempts()) {
      ObjectNode node = attempts.addObject();
      node.put("attempt_id", attempt.attemptId().toString());
      node.put("revision", attempt.revision());
      node.put("route_variant_id", attempt.routeVariantId());
      node.put("occurred_at", Timestamps.format(attempt.occurredAt()));
      node.put("sent", attempt.sent());
      if (attempt.gradeNumeric() == null) {
        node.putNull("grade_numeric");
      } else {
        node.put("grade_numeric", attempt.gradeNumeric());
      }
      node.put("status", attempt.status());
    }
    ObjectNode expected = root.putObject("expected");
    Expected e = fixture.expected();
    if (e.featureAsOf() == null) {
      expected.putNull("feature_as_of");
    } else {
      expected.put("feature_as_of", Timestamps.format(e.featureAsOf()));
    }
    expected.put("provenance_checksum", e.provenanceChecksum());
    expected.put("aggregates_json", e.aggregatesJson());
    return MAPPER.writeValueAsString(root) + "\n";
  }
}
