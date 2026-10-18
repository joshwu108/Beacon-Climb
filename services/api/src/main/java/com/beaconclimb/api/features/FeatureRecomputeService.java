package com.beaconclimb.api.features;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.instant;
import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import com.beaconclimb.features.AttemptFeatureRow;
import com.beaconclimb.features.FeatureAggregates;
import com.beaconclimb.features.FeatureComputer;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The synchronous canonical feature recompute (BC-0113): the parity oracle of ADR-0004. One
 * parameterised query selects the current revision of every ACTIVE attempt of the user with {@code
 * occurred_at <= W} joined to the variant's grade; {@link FeatureComputer} folds the rows and the
 * snapshot is upserted as source SYNC. Read and write share one transaction so the provenance
 * checksum describes exactly the rows that produced the aggregates.
 *
 * <p>The live row is monotonic in {@code cutoff_w} ({@link FeatureSnapshotRepository}): a recompute
 * at a cutoff older than the stored row returns the computed snapshot with {@link Outcome#stored()}
 * false and leaves the row untouched. Parity oracle runs at historical cutoffs (BC-0306) must
 * therefore target a separate comparison, not the live row that serves recommendations.
 */
@Service
public class FeatureRecomputeService {

  private static final Logger log = LoggerFactory.getLogger(FeatureRecomputeService.class);

  /** The latest revision per attempt_id is the attempts row itself (attempt_revisions is audit). */
  private static final String SELECT_INCLUDED =
      "SELECT a.attempt_id, a.revision, a.route_variant_public_id, a.occurred_at, a.sent,"
          + " v.grade_numeric FROM attempts a JOIN route_variants v ON v.variant_id = a.variant_id"
          + " WHERE a.user_id = :user_id AND a.status = 'ACTIVE' AND a.occurred_at <= :w"
          + " ORDER BY a.occurred_at DESC, a.attempt_id DESC";

  private final JdbcClient jdbc;
  private final FeatureSnapshotRepository snapshots;
  private final TransactionTemplate tx;
  private final Clock clock;

  public FeatureRecomputeService(
      JdbcClient jdbc, FeatureSnapshotRepository snapshots, TransactionTemplate tx, Clock clock) {
    this.jdbc = jdbc;
    this.snapshots = snapshots;
    this.tx = tx;
    this.clock = clock;
  }

  /** The computed snapshot and whether the live row now holds it (false: older cutoff). */
  public record Outcome(FeatureSnapshot snapshot, boolean stored) {}

  public Outcome recompute(UUID userId, Instant cutoffW) {
    Instant computedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    Outcome outcome = tx.execute(status -> recomputeInTransaction(userId, cutoffW, computedAt));
    FeatureSnapshot snapshot = outcome.snapshot();
    log.info(
        "features outcome=RECOMPUTED user_id={} cutoff_w={} feature_as_of={} checksum={} stored={}",
        userId,
        cutoffW,
        snapshot.featureAsOf(),
        snapshot.provenanceChecksum(),
        outcome.stored());
    return outcome;
  }

  /**
   * The pure batch computation at {@code cutoffW} without touching {@code user_feature_snapshots}
   * (BC-0306 parity oracle): the same query and the same shared computer as {@link #recompute},
   * returned as an unstored SYNC-shaped snapshot. One read, no transaction, no log line.
   */
  public FeatureSnapshot compute(UUID userId, Instant cutoffW) {
    return computed(userId, cutoffW, clock.instant().truncatedTo(ChronoUnit.MILLIS));
  }

  /** Every account that is not erased, in id order, for {@code --recompute.user=all}. */
  public List<UUID> recomputableUserIds() {
    return jdbc.sql("SELECT id FROM users WHERE status <> 'DELETED' ORDER BY id")
        .query(UUID.class)
        .list();
  }

  private Outcome recomputeInTransaction(UUID userId, Instant cutoffW, Instant computedAt) {
    FeatureSnapshot snapshot = computed(userId, cutoffW, computedAt);
    boolean stored = snapshots.upsert(snapshot);
    return new Outcome(snapshot, stored);
  }

  private FeatureSnapshot computed(UUID userId, Instant cutoffW, Instant computedAt) {
    List<AttemptFeatureRow> rows =
        jdbc.sql(SELECT_INCLUDED)
            .param("user_id", userId)
            .param("w", timestamp(cutoffW))
            .query(FeatureRecomputeService::row)
            .list();
    FeatureComputer.Result computed = FeatureComputer.compute(rows, cutoffW);
    return new FeatureSnapshot(
        userId,
        FeatureAggregates.SCHEMA_VERSION,
        cutoffW,
        computed.featureAsOf(),
        computed.aggregates(),
        computed.checksum(),
        FeatureSource.SYNC,
        null,
        null,
        computedAt);
  }

  private static AttemptFeatureRow row(ResultSet rs, int rowNum) throws SQLException {
    BigDecimal grade = rs.getBigDecimal("grade_numeric");
    return new AttemptFeatureRow(
        rs.getObject("attempt_id", UUID.class),
        rs.getInt("revision"),
        rs.getString("route_variant_public_id"),
        instant(rs, "occurred_at"),
        rs.getBoolean("sent"),
        grade == null ? null : grade.doubleValue());
  }
}
