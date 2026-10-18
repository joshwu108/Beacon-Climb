package com.beaconclimb.api.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.features.FeatureAggregates;
import com.beaconclimb.api.features.FeatureComputer;
import com.beaconclimb.api.features.FeatureRecomputeService;
import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.features.FeatureSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * The repair job's control flow and the contracted output lines without a database: resolution from
 * the previous checksum, ERROR recorded on the rows of a user whose recompute throws while the
 * other users still run, and the exit code.
 */
class ReconcileRunnerTest {

  private static final Instant NOW = Instant.parse("2026-10-18T10:00:00.123456Z");
  private static final Instant W = Instant.parse("2026-10-18T10:00:00.123Z");
  private static final UUID USER_A = UUID.fromString("018f0000-0000-7000-8000-00000000000a");
  private static final UUID USER_B = UUID.fromString("018f0000-0000-7000-8000-00000000000b");
  private static final UUID USER_C = UUID.fromString("018f0000-0000-7000-8000-00000000000c");
  private static final String CHECKSUM_A = "a".repeat(64);
  private static final String CHECKSUM_C = "c".repeat(64);

  /** Open rows per user, closed or marked by the service. */
  private static final class FakeRepository extends ReconciliationRepository {
    final Map<UUID, Integer> open = new HashMap<>();
    final List<String> calls = new ArrayList<>();

    FakeRepository() {
      super(null);
    }

    @Override
    public List<UUID> openUserIds() {
      return open.keySet().stream().sorted().toList();
    }

    @Override
    public long countOpen() {
      return open.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public int resolveOpen(
        UUID userId, Instant resolvedAt, Resolution resolution, Instant cutoffW, String checksum) {
      calls.add("resolve " + userId + " " + resolution + " " + cutoffW + " " + checksum);
      return open.remove(userId);
    }

    @Override
    public int markError(UUID userId) {
      calls.add("error " + userId);
      return open.get(userId);
    }
  }

  /** The live SYNC checksums; {@code null} means no row. */
  private static final class FakeSnapshots extends FeatureSnapshotRepository {
    final Map<UUID, String> checksums = new HashMap<>();

    FakeSnapshots() {
      super(null);
    }

    @Override
    public Optional<FeatureSnapshot> find(UUID userId, int version) {
      return Optional.ofNullable(checksums.get(userId)).map(c -> snapshot(userId, W, c));
    }
  }

  /** Recomputes to a fixed checksum per user; throws for {@link #failing}. */
  private static final class FakeFeatures extends FeatureRecomputeService {
    final Map<UUID, String> results = new HashMap<>();
    UUID failing;

    FakeFeatures() {
      super(null, null, null, null);
    }

    @Override
    public Outcome recompute(UUID userId, Instant cutoffW) {
      if (userId.equals(failing)) {
        throw new IllegalStateException("simulated recompute failure");
      }
      return new Outcome(snapshot(userId, cutoffW, results.get(userId)), true);
    }
  }

  private static FeatureSnapshot snapshot(UUID userId, Instant cutoffW, String checksum) {
    return new FeatureSnapshot(
        userId,
        FeatureAggregates.SCHEMA_VERSION,
        cutoffW,
        null,
        FeatureComputer.compute(List.of(), cutoffW).aggregates(),
        checksum,
        FeatureSource.SYNC,
        null,
        null,
        cutoffW);
  }

  private final FakeRepository repository = new FakeRepository();
  private final FakeSnapshots snapshots = new FakeSnapshots();
  private final FakeFeatures features = new FakeFeatures();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final ReconcileService service =
      new ReconcileService(
          repository,
          features,
          snapshots,
          new ReconciliationMetrics(new SimpleMeterRegistry(), repository, clock),
          clock);

  @Test
  void resolvesEveryOpenUserRecordsErrorsAndExitsOne() {
    repository.open.put(USER_A, 2);
    repository.open.put(USER_B, 1);
    repository.open.put(USER_C, 3);
    snapshots.checksums.put(USER_A, CHECKSUM_A); // unchanged by the recompute
    snapshots.checksums.put(USER_C, "0".repeat(64)); // stale
    features.results.put(USER_A, CHECKSUM_A);
    features.results.put(USER_C, CHECKSUM_C);
    features.failing = USER_B;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ReconcileRunner runner = new ReconcileRunner(service, new PrintStream(out, true));

    runner.run(new DefaultApplicationArguments());

    assertThat(runner.getExitCode()).isEqualTo(1);
    assertThat(repository.calls)
        .containsExactly(
            "resolve " + USER_A + " ALREADY_CURRENT " + W + " " + CHECKSUM_A,
            "error " + USER_B,
            "resolve " + USER_C + " RECONCILED " + W + " " + CHECKSUM_C);
    assertThat(repository.open).containsOnlyKeys(USER_B);
    String printed = out.toString(StandardCharsets.UTF_8);
    assertThat(printed.lines())
        .containsExactly(
            "reconciled user_id="
                + USER_A
                + " rows=2 cutoff_w="
                + W
                + " checksum="
                + CHECKSUM_A
                + " resolution=ALREADY_CURRENT",
            "reconciled user_id="
                + USER_B
                + " rows=1 cutoff_w="
                + W
                + " checksum=null resolution=ERROR"
                + " error=java.lang.IllegalStateException: simulated recompute failure",
            "reconciled user_id="
                + USER_C
                + " rows=3 cutoff_w="
                + W
                + " checksum="
                + CHECKSUM_C
                + " resolution=RECONCILED",
            "reconcile summary users=3 rows=6 reconciled=1 already_current=1 errors=1");
  }

  @Test
  void nothingOpenPrintsTheEmptySummaryAndExitsZero() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ReconcileRunner runner = new ReconcileRunner(service, new PrintStream(out, true));

    runner.run(new DefaultApplicationArguments());

    assertThat(runner.getExitCode()).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8).lines())
        .containsExactly(
            "reconcile summary users=0 rows=0 reconciled=0 already_current=0 errors=0");
  }

  @Test
  void noPreviousSyncRowIsReconciled() {
    repository.open.put(USER_A, 1);
    features.results.put(USER_A, CHECKSUM_A);

    ReconcileService.UserResult result = service.reconcileUser(USER_A);

    assertThat(result.resolution()).isEqualTo(Resolution.RECONCILED);
    assertThat(result.cutoffW()).isEqualTo(W);
    assertThat(result.failed()).isFalse();
  }
}
