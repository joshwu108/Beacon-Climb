package com.beaconclimb.api.features;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.instant;
import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

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

  public FeatureSnapshot recompute(UUID userId, Instant cutoffW) {
    Instant computedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    FeatureSnapshot snapshot =
        tx.execute(status -> recomputeInTransaction(userId, cutoffW, computedAt));
    log.info(
        "features outcome=RECOMPUTED user_id={} cutoff_w={} feature_as_of={} checksum={}",
        userId,
        cutoffW,
        snapshot.featureAsOf(),
        snapshot.provenanceChecksum());
    return snapshot;
  }

  /** Every account that is not erased, in id order, for {@code --recompute.user=all}. */
  public List<UUID> recomputableUserIds() {
    return jdbc.sql("SELECT id FROM users WHERE status <> 'DELETED' ORDER BY id")
        .query(UUID.class)
        .list();
  }

  private FeatureSnapshot recomputeInTransaction(UUID userId, Instant cutoffW, Instant computedAt) {
    List<AttemptFeatureRow> rows =
        jdbc.sql(SELECT_INCLUDED)
            .param("user_id", userId)
            .param("w", timestamp(cutoffW))
            .query(FeatureRecomputeService::row)
            .list();
    FeatureComputer.Result computed = FeatureComputer.compute(rows, cutoffW);
    FeatureSnapshot snapshot =
        new FeatureSnapshot(
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
    snapshots.upsert(snapshot);
    return snapshot;
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
