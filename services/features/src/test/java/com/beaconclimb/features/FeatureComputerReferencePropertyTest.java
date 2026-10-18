package com.beaconclimb.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pure half of the api's FeatureRecomputePropertyTest, without Postgres: a seeded random
 * multiset of rows (duplicates, several revisions of one attempt, rows after W, null grades, ties)
 * fed to the computer as-is must equal the {@link ReferenceFeatures} restatement over the
 * de-duplicated set. Seeds are fixed so a failure is reproducible.
 */
class FeatureComputerReferencePropertyTest {

  private static final Instant W = Instant.parse("2026-10-14T14:00:00Z");
  private static final int TRIES = 300;
  private static final int MAX_ROWS = 40;

  /** Hour offsets back from W, chosen so that windows, ties and session gaps all occur. */
  private static final int[] OFFSET_HOURS = {
    0, 1, 2, 3, 4, 7, 24, 48, 167, 168, 169, 400, 719, 720, 721, 1500, 2159, 2160, 2161, 2400, -1
  };

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  @DisplayName(
      "computer(rows as delivered) == reference(highest revision per attempt) for 300 seeds")
  void computerMatchesReferenceOverDeduplicatedRows() {
    for (int seed = 0; seed < TRIES; seed++) {
      Random random = new Random(seed);
      List<AttemptFeatureRow> delivered = rows(random);

      FeatureComputer.Result actual = FeatureComputer.compute(delivered, W);
      ReferenceFeatures.Expected expected =
          ReferenceFeatures.compute(deduplicate(delivered), W, JSON);

      assertThat(actual.aggregates().toJson())
          .as("seed %d", seed)
          .isEqualTo(expected.aggregatesJson());
      assertThat(actual.checksum()).as("seed %d", seed).isEqualTo(expected.checksum());
      assertThat(actual.featureAsOf()).as("seed %d", seed).isEqualTo(expected.featureAsOf());
    }
  }

  private static List<AttemptFeatureRow> rows(Random random) {
    int count = random.nextInt(MAX_ROWS + 1);
    List<UUID> ids = new ArrayList<>();
    List<AttemptFeatureRow> rows = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      UUID id =
          ids.isEmpty() || random.nextInt(4) != 0
              ? TestIds.uuid7()
              : ids.get(random.nextInt(ids.size()));
      ids.add(id);
      Instant at =
          W.minus(Duration.ofHours(OFFSET_HOURS[random.nextInt(OFFSET_HOURS.length)]))
              .minusSeconds(random.nextInt(7) * 600L)
              .plusMillis(random.nextInt(3) == 0 ? random.nextInt(1000) : 0);
      Double grade = random.nextInt(5) == 0 ? null : 10.0 + random.nextInt(80) / 4.0;
      rows.add(
          new AttemptFeatureRow(
              id,
              1 + random.nextInt(4),
              "synthetic-route-" + random.nextInt(25) + ":40",
              at,
              random.nextBoolean(),
              grade));
    }
    return rows;
  }

  /** Highest revision per attempt_id, first seen among equal revisions. */
  private static List<ReferenceFeatures.Row> deduplicate(List<AttemptFeatureRow> rows) {
    Map<UUID, AttemptFeatureRow> latest = new LinkedHashMap<>();
    for (AttemptFeatureRow row : rows) {
      latest.merge(
          row.attemptId(), row, (kept, next) -> next.revision() > kept.revision() ? next : kept);
    }
    List<ReferenceFeatures.Row> out = new ArrayList<>();
    for (AttemptFeatureRow row : latest.values()) {
      out.add(
          new ReferenceFeatures.Row(
              row.attemptId(),
              row.revision(),
              row.routeVariantId(),
              row.occurredAt(),
              row.sent(),
              row.gradeNumeric()));
    }
    return out;
  }
}
