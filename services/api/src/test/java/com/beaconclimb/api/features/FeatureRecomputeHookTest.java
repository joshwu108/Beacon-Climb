package com.beaconclimb.api.features;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A failing recompute is logged and swallowed: the committed attempt write must stand. */
class FeatureRecomputeHookTest {

  @Test
  void recomputeFailureNeverPropagates() {
    FeatureRecomputeService failing =
        new FeatureRecomputeService(null, null, null, null) {
          @Override
          public Outcome recompute(UUID userId, Instant cutoffW) {
            throw new IllegalStateException("database unavailable");
          }
        };
    FeatureRecomputeHook hook =
        new FeatureRecomputeHook(
            failing, Clock.fixed(Instant.parse("2026-10-14T14:00:00Z"), ZoneOffset.UTC));

    assertThatCode(() -> hook.afterAttemptWrite(UUID.randomUUID())).doesNotThrowAnyException();
  }
}
