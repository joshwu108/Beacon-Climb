package com.beaconclimb.api.reconciliation;

import com.beaconclimb.api.features.FeatureRecomputeService;
import com.beaconclimb.api.features.FeatureSnapshot;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.features.FeatureAggregates;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The repair job behind {@code make reconcile} (BC-0309, ADR-0004 "Events beyond 24 h enter
 * reconciliation; a batch rebuild publishes a newer sequence"). For every user with an open {@code
 * reconciliation_events} row it recomputes the SYNC snapshot through {@link
 * FeatureRecomputeService} at {@code W = now} (millisecond-truncated clock, the same code path and
 * the same {@code W} rule as the attempts hook) and then closes the user's open rows.
 *
 * <p>Resolution: {@link Resolution#RECONCILED} when the SYNC row's {@code provenance_checksum}
 * changed (or there was no SYNC row), {@link Resolution#ALREADY_CURRENT} when the recompute
 * produced the checksum the row already carried. ALREADY_CURRENT is the common case and is not a
 * wasted run: the api writes every attempt to Postgres and its hook recomputes the SYNC row from
 * Postgres in the same request, so an event is "late" only for the STREAM row, whose Flink state
 * never applied it. The recompute here proves that at an explicit {@code W} and records that {@code
 * W} and checksum on the rows ({@code resolved_cutoff_w}, {@code resolved_checksum}).
 *
 * <p>Two transactions per user, deliberately: the recompute commits first (its own transaction
 * inside {@link FeatureRecomputeService}), then the rows are closed. A crash in between leaves the
 * rows open and the next run recomputes again, which is idempotent. {@code W = now} is always at
 * least the stored SYNC {@code cutoff_w} written by the hook with the same clock, so the monotonic
 * rule of {@link FeatureSnapshotRepository#upsert} lets the write through; should a row be newer
 * (an explicit future {@code make recompute-features W=...} inside the five-minute skew), the live
 * row is read back and judged instead, since a SYNC recompute at a later cutoff already included
 * the late attempt.
 *
 * <p>A failed user is recorded as {@link Resolution#ERROR} on its open rows (which stay open), the
 * remaining users still run, and the caller exits 1.
 */
@Service
public class ReconcileService {

  private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

  /** One user's outcome; {@code checksum} is null and {@code error} set only for ERROR. */
  public record UserResult(
      UUID userId,
      int rows,
      Instant cutoffW,
      String checksum,
      Resolution resolution,
      String error) {

    public boolean failed() {
      return resolution == Resolution.ERROR;
    }
  }

  /** The whole run. */
  public record Report(List<UserResult> users) {

    public Report {
      users = List.copyOf(users);
    }

    public int rows() {
      return users.stream().mapToInt(UserResult::rows).sum();
    }

    public long count(Resolution resolution) {
      return users.stream().filter(u -> u.resolution() == resolution).count();
    }

    public boolean anyFailed() {
      return users.stream().anyMatch(UserResult::failed);
    }
  }

  private final ReconciliationRepository repository;
  private final FeatureRecomputeService features;
  private final FeatureSnapshotRepository snapshots;
  private final ReconciliationMetrics metrics;
  private final Clock clock;

  public ReconcileService(
      ReconciliationRepository repository,
      FeatureRecomputeService features,
      FeatureSnapshotRepository snapshots,
      ReconciliationMetrics metrics,
      Clock clock) {
    this.repository = repository;
    this.features = features;
    this.snapshots = snapshots;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** Repairs every user with an open row, in id order; never throws for one user's failure. */
  public Report reconcileAll() {
    List<UserResult> results = new ArrayList<>();
    for (UUID userId : repository.openUserIds()) {
      results.add(reconcileUser(userId));
    }
    metrics.refreshOpenRows();
    return new Report(results);
  }

  /** Repairs one user at {@code W = now}. */
  public UserResult reconcileUser(UUID userId) {
    Instant w = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    try {
      UserResult result = repair(userId, w);
      metrics.resolved(result.resolution(), result.rows());
      log.info(
          "reconcile outcome={} user_id={} rows={} cutoff_w={} checksum={}",
          result.resolution(),
          userId,
          result.rows(),
          result.cutoffW(),
          result.checksum());
      return result;
    } catch (RuntimeException e) {
      log.error("reconcile outcome=ERROR user_id={} cutoff_w={}", userId, w, e);
      int rows = markError(userId);
      metrics.resolved(Resolution.ERROR, rows);
      return new UserResult(userId, rows, w, null, Resolution.ERROR, e.toString());
    }
  }

  private UserResult repair(UUID userId, Instant w) {
    Optional<String> previous =
        snapshots
            .find(userId, FeatureAggregates.SCHEMA_VERSION)
            .map(FeatureSnapshot::provenanceChecksum);
    FeatureRecomputeService.Outcome outcome = features.recompute(userId, w);
    FeatureSnapshot live =
        outcome.stored()
            ? outcome.snapshot()
            : snapshots
                .find(userId, FeatureAggregates.SCHEMA_VERSION)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "recompute at " + w + " was not stored and no SYNC row exists"));
    Resolution resolution =
        previous.filter(live.provenanceChecksum()::equals).isPresent()
            ? Resolution.ALREADY_CURRENT
            : Resolution.RECONCILED;
    Instant resolvedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    int rows =
        repository.resolveOpen(
            userId, resolvedAt, resolution, live.cutoffW(), live.provenanceChecksum());
    return new UserResult(
        userId, rows, live.cutoffW(), live.provenanceChecksum(), resolution, null);
  }

  private int markError(UUID userId) {
    try {
      return repository.markError(userId);
    } catch (RuntimeException e) {
      log.error("reconcile could not record ERROR user_id={}", userId, e);
      return 0;
    }
  }
}
