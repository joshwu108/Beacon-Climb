package com.beaconclimb.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.features.FeatureComputer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** Argument handling and the contracted output line of the recompute CLI, without a database. */
class FeatureRecomputeRunnerTest {

  private static final Instant NOW = Instant.parse("2026-10-14T14:00:00Z");
  private static final UUID USER_A = UUID.fromString("018f0000-0000-7000-8000-00000000000a");
  private static final UUID USER_B = UUID.fromString("018f0000-0000-7000-8000-00000000000b");

  /** Records the requested recomputes instead of touching Postgres. */
  private static final class RecordingService extends FeatureRecomputeService {
    final List<String> calls = new ArrayList<>();

    RecordingService() {
      super(null, null, null, null);
    }

    boolean stored = true;

    @Override
    public Outcome recompute(UUID userId, Instant cutoffW) {
      calls.add(userId + "@" + cutoffW);
      return new Outcome(
          new FeatureSnapshot(
              userId,
              1,
              cutoffW,
              null,
              FeatureComputer.compute(List.of(), cutoffW).aggregates(),
              "0".repeat(64),
              FeatureSource.SYNC,
              null,
              null,
              cutoffW),
          stored);
    }

    @Override
    public List<UUID> recomputableUserIds() {
      return List.of(USER_A, USER_B);
    }
  }

  @Test
  void allUsersAtExplicitCutoffExitsZero() {
    RecordingService service = new RecordingService();
    FeatureRecomputeRunner runner =
        new FeatureRecomputeRunner(
            service, new RecomputeProperties("all", "2026-10-01T00:00:00Z"), clock());

    runner.run(new DefaultApplicationArguments());

    assertThat(runner.getExitCode()).isZero();
    assertThat(service.calls)
        .containsExactly(USER_A + "@2026-10-01T00:00:00Z", USER_B + "@2026-10-01T00:00:00Z");
  }

  @Test
  void singleUserDefaultsToTheClockAndPrintsTheContractedLine() {
    RecordingService service = new RecordingService();
    FeatureRecomputeRunner runner =
        new FeatureRecomputeRunner(
            service, new RecomputeProperties(USER_A.toString(), null), clock());

    runner.run(new DefaultApplicationArguments());

    assertThat(runner.getExitCode()).isZero();
    assertThat(service.calls).containsExactly(USER_A + "@" + NOW);
    assertThat(FeatureRecomputeRunner.line(service.recompute(USER_A, NOW)))
        .isEqualTo(
            "recomputed user_id="
                + USER_A
                + " cutoff_w=2026-10-14T14:00:00Z feature_as_of=null checksum="
                + "0".repeat(64));
  }

  @Test
  void unstoredRecomputeAppendsTheOlderCutoffSuffixAndStillExitsZero() {
    RecordingService service = new RecordingService();
    service.stored = false;
    FeatureRecomputeRunner runner =
        new FeatureRecomputeRunner(
            service, new RecomputeProperties(USER_A.toString(), null), clock());

    runner.run(new DefaultApplicationArguments());

    assertThat(runner.getExitCode()).isZero();
    assertThat(FeatureRecomputeRunner.line(service.recompute(USER_A, NOW)))
        .endsWith(" checksum=" + "0".repeat(64) + " stored=false (older cutoff than live row)");
  }

  @Test
  void badUserOrCutoffIsAUsageErrorWithoutRecompute() {
    RecordingService service = new RecordingService();

    FeatureRecomputeRunner badUser =
        new FeatureRecomputeRunner(service, new RecomputeProperties("nobody", null), clock());
    badUser.run(new DefaultApplicationArguments());
    FeatureRecomputeRunner badCutoff =
        new FeatureRecomputeRunner(service, new RecomputeProperties("all", "yesterday"), clock());
    badCutoff.run(new DefaultApplicationArguments());

    assertThat(badUser.getExitCode()).isEqualTo(2);
    assertThat(badCutoff.getExitCode()).isEqualTo(2);
    assertThat(service.calls).isEqualTo(Collections.emptyList());
  }

  @Test
  void futureCutoffIsAUsageErrorSoTheLiveRowCannotBePinnedAhead() {
    RecordingService service = new RecordingService();
    String tooLate = NOW.plus(FeatureRecomputeRunner.MAX_FUTURE_SKEW).plusSeconds(1).toString();
    String withinSkew = NOW.plus(FeatureRecomputeRunner.MAX_FUTURE_SKEW).toString();

    FeatureRecomputeRunner future =
        new FeatureRecomputeRunner(service, new RecomputeProperties("all", tooLate), clock());
    future.run(new DefaultApplicationArguments());
    FeatureRecomputeRunner allowed =
        new FeatureRecomputeRunner(service, new RecomputeProperties("all", withinSkew), clock());
    allowed.run(new DefaultApplicationArguments());

    assertThat(future.getExitCode()).isEqualTo(2);
    assertThat(allowed.getExitCode()).isZero();
  }

  private static Clock clock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }
}
