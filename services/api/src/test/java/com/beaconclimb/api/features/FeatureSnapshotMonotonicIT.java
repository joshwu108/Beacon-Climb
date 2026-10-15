package com.beaconclimb.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.recommendations.RecommendationsTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * BC-0124 HIGH-2: the live snapshot is monotonic in {@code cutoff_w}. After the attempts hook has
 * stored a warm snapshot at {@code W = now}, a recompute at an older cutoff returns what it
 * computed but is not stored, the live row keeps its newer cutoff, and a recommendation still sees
 * the user as warm. An equal or newer cutoff still replaces the row.
 */
class FeatureSnapshotMonotonicIT extends RecommendationsTestSupport {

  private static final Instant T = Instant.parse("2026-10-14T14:00:00Z");

  @Autowired FeatureRecomputeService features;
  @Autowired FeatureSnapshotRepository snapshots;

  @BeforeEach
  void pinClock() {
    testClock.fix(T);
  }

  @Test
  void olderCutoffRecomputeLeavesTheLiveRowAndTheUserStaysWarm() {
    Account account = registerReady("INTERMEDIATE");
    Instant occurredAt = T.minus(Duration.ofHours(3));
    attemptAt(account, candidateIds().get(0), occurredAt);
    FeatureSnapshot live = find(account.userId());
    assertThat(live.cutoffW()).isEqualTo(T);
    assertThat(live.featureAsOf()).isEqualTo(occurredAt);

    FeatureRecomputeService.Outcome stale =
        features.recompute(account.userId(), T.minus(Duration.ofDays(1)));

    assertThat(stale.stored()).isFalse();
    assertThat(stale.snapshot().cutoffW()).isEqualTo(T.minus(Duration.ofDays(1)));
    assertThat(stale.snapshot().featureAsOf()).isNull();
    assertThat(find(account.userId())).isEqualTo(live);
    JsonNode response =
        recommendOk(
            account.token(), recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of()));
    assertThat(response.get("cold_start").asBoolean()).isFalse();
    assertThat(response.get("feature_as_of").asString()).isEqualTo(occurredAt.toString());
  }

  @Test
  void equalAndNewerCutoffsStillReplaceTheLiveRow() {
    Account account = registerReady("INTERMEDIATE");
    attemptAt(account, candidateIds().get(0), T.minus(Duration.ofHours(3)));

    FeatureRecomputeService.Outcome same = features.recompute(account.userId(), T);
    assertThat(same.stored()).isTrue();
    assertThat(find(account.userId())).isEqualTo(same.snapshot());

    FeatureRecomputeService.Outcome newer =
        features.recompute(account.userId(), T.plus(Duration.ofSeconds(1)));
    assertThat(newer.stored()).isTrue();
    assertThat(find(account.userId())).isEqualTo(newer.snapshot());
    assertThat(find(account.userId()).cutoffW()).isEqualTo(T.plus(Duration.ofSeconds(1)));
  }

  private FeatureSnapshot find(UUID userId) {
    return snapshots.find(userId, FeatureAggregates.SCHEMA_VERSION).orElseThrow();
  }
}
