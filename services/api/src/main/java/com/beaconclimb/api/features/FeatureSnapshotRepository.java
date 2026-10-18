package com.beaconclimb.api.features;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.instant;
import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import com.beaconclimb.api.platform.json.Jcs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code user_feature_snapshots} access. Since V9 (BC-0305) the primary key is {@code (user_id,
 * feature_schema_version, source)}: the attempts hook and the recompute own the SYNC row, the
 * materializer owns the STREAM row, and the two coexist.
 *
 * <p>SYNC row, monotonic rule (BC-0124 HIGH-2): the live SYNC row of a {@code (user_id,
 * feature_schema_version)} only ever moves forward in {@code cutoff_w}. An upsert whose {@code
 * cutoff_w} is older than the stored one is a no-op ({@code ON CONFLICT ... DO UPDATE ... WHERE
 * stored.cutoff_w <= EXCLUDED.cutoff_w}), so a recompute at an explicit past cutoff can never
 * replace the snapshot the attempts hook just wrote at {@code W = now} and silently turn a warm
 * user cold. An equal cutoff does replace, which is what a repeat recompute after a correction
 * relies on.
 *
 * <p>STREAM row (ADR-0004): guarded by the Kafka position, not by any watermark. {@link
 * #lockStream} reads the stored row {@code FOR UPDATE} so the materializer decides apply /
 * duplicate / stale / conflict under the row lock; {@link #upsertStream} then writes, re-checking
 * in SQL that the stored offset is lower for the same partition (defence in depth, the decision is
 * the service's). {@link #find} keeps returning the SYNC row: the recommendation read rule changes
 * in BC-0310 part 2, which will build on {@link #findBoth}.
 */
@Repository
public class FeatureSnapshotRepository {

  private static final String COLUMNS =
      "user_id, feature_schema_version, cutoff_w, feature_as_of,"
          + " aggregates_json::text AS aggregates_json, provenance_checksum, source,"
          + " source_partition, source_offset, computed_at";
  private static final String SELECT_ONE =
      "SELECT "
          + COLUMNS
          + " FROM user_feature_snapshots WHERE user_id = :user_id"
          + " AND feature_schema_version = :version AND source = :source";

  private final JdbcClient jdbc;

  public FeatureSnapshotRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The SYNC and STREAM rows of one {@code (user_id, feature_schema_version)}, either absent. */
  public record Both(Optional<FeatureSnapshot> sync, Optional<FeatureSnapshot> stream) {}

  /**
   * Inserts or replaces the live SYNC row and returns true; returns false (and leaves the row
   * alone) when the stored {@code cutoff_w} is newer than {@code snapshot.cutoffW()}. Only the SYNC
   * row goes through here; the materializer writes with {@link #upsertStream}.
   */
  public boolean upsert(FeatureSnapshot snapshot) {
    if (snapshot.source() != FeatureSource.SYNC) {
      throw new IllegalArgumentException(
          "upsert writes the SYNC row only; STREAM rows go through upsertStream");
    }
    int rows =
        jdbc.sql(
                "INSERT INTO user_feature_snapshots (user_id, feature_schema_version, cutoff_w,"
                    + " feature_as_of, aggregates_json, provenance_checksum, source,"
                    + " source_partition, source_offset, computed_at) VALUES (:user_id, :version,"
                    + " :cutoff_w, :as_of, CAST(:aggregates AS jsonb), :checksum, 'SYNC',"
                    + " NULL, NULL, :computed_at)"
                    + " ON CONFLICT (user_id, feature_schema_version, source) DO UPDATE SET"
                    + " cutoff_w = EXCLUDED.cutoff_w, feature_as_of = EXCLUDED.feature_as_of,"
                    + " aggregates_json = EXCLUDED.aggregates_json,"
                    + " provenance_checksum = EXCLUDED.provenance_checksum,"
                    + " computed_at = EXCLUDED.computed_at"
                    + " WHERE user_feature_snapshots.cutoff_w <= EXCLUDED.cutoff_w")
            .param("user_id", snapshot.userId())
            .param("version", snapshot.featureSchemaVersion())
            .param("cutoff_w", timestamp(snapshot.cutoffW()))
            .param("as_of", timestamp(snapshot.featureAsOf()))
            .param("aggregates", snapshot.aggregates().toJson())
            .param("checksum", snapshot.provenanceChecksum())
            .param("computed_at", timestamp(snapshot.computedAt()))
            .update();
    return rows == 1;
  }

  /** The SYNC row (the attempts hook's and the recompute's), the read rule of BC-0310 part 1. */
  public Optional<FeatureSnapshot> find(UUID userId, int featureSchemaVersion) {
    return findBySource(userId, featureSchemaVersion, FeatureSource.SYNC);
  }

  /** The STREAM row the materializer wrote, if any. */
  public Optional<FeatureSnapshot> findStream(UUID userId, int featureSchemaVersion) {
    return findBySource(userId, featureSchemaVersion, FeatureSource.STREAM);
  }

  /** Both rows in one query, for the BC-0310 part 2 freshness rule. */
  public Both findBoth(UUID userId, int featureSchemaVersion) {
    List<FeatureSnapshot> rows =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM user_feature_snapshots WHERE user_id = :user_id"
                    + " AND feature_schema_version = :version")
            .param("user_id", userId)
            .param("version", featureSchemaVersion)
            .query(FeatureSnapshotRepository::row)
            .list();
    return new Both(
        rows.stream().filter(r -> r.source() == FeatureSource.SYNC).findFirst(),
        rows.stream().filter(r -> r.source() == FeatureSource.STREAM).findFirst());
  }

  /**
   * The STREAM row locked {@code FOR UPDATE} for the rest of the caller's transaction (no lock when
   * the row does not exist yet; the materializer runs one consumer thread per partition set, and
   * {@link #upsertStream} re-checks the offset in SQL).
   */
  public Optional<FeatureSnapshot> lockStream(UUID userId, int featureSchemaVersion) {
    return jdbc.sql(SELECT_ONE + " FOR UPDATE")
        .param("user_id", userId)
        .param("version", featureSchemaVersion)
        .param("source", FeatureSource.STREAM.name())
        .query(FeatureSnapshotRepository::row)
        .optional();
  }

  /**
   * Inserts or replaces the STREAM row and returns true; returns false when a stored row exists
   * whose position is not strictly older on the same partition (the service has already decided
   * under the lock, so false here means a concurrent writer and the caller should not acknowledge).
   */
  public boolean upsertStream(StreamSnapshot snapshot) {
    int rows =
        jdbc.sql(
                "INSERT INTO user_feature_snapshots (user_id, feature_schema_version, cutoff_w,"
                    + " feature_as_of, aggregates_json, provenance_checksum, source,"
                    + " source_partition, source_offset, computed_at) VALUES (:user_id, :version,"
                    + " :cutoff_w, :as_of, CAST(:aggregates AS jsonb), :checksum, 'STREAM',"
                    + " :partition, :offset, :computed_at)"
                    + " ON CONFLICT (user_id, feature_schema_version, source) DO UPDATE SET"
                    + " cutoff_w = EXCLUDED.cutoff_w, feature_as_of = EXCLUDED.feature_as_of,"
                    + " aggregates_json = EXCLUDED.aggregates_json,"
                    + " provenance_checksum = EXCLUDED.provenance_checksum,"
                    + " source_partition = EXCLUDED.source_partition,"
                    + " source_offset = EXCLUDED.source_offset, computed_at = EXCLUDED.computed_at"
                    + " WHERE user_feature_snapshots.source_partition = EXCLUDED.source_partition"
                    + " AND user_feature_snapshots.source_offset < EXCLUDED.source_offset")
            .param("user_id", snapshot.userId())
            .param("version", FeatureAggregates.SCHEMA_VERSION)
            .param("cutoff_w", timestamp(snapshot.cutoffW()))
            .param("as_of", timestamp(snapshot.featureAsOf()))
            .param("aggregates", snapshot.aggregatesJson())
            .param("checksum", snapshot.provenanceChecksum())
            .param("partition", snapshot.sourcePartition())
            .param("offset", snapshot.sourceOffset())
            .param("computed_at", timestamp(snapshot.computedAt()))
            .update();
    return rows == 1;
  }

  private Optional<FeatureSnapshot> findBySource(
      UUID userId, int featureSchemaVersion, FeatureSource source) {
    return jdbc.sql(SELECT_ONE)
        .param("user_id", userId)
        .param("version", featureSchemaVersion)
        .param("source", source.name())
        .query(FeatureSnapshotRepository::row)
        .optional();
  }

  private static FeatureSnapshot row(ResultSet rs, int rowNum) throws SQLException {
    int partition = rs.getInt("source_partition");
    boolean partitionNull = rs.wasNull();
    long offset = rs.getLong("source_offset");
    boolean offsetNull = rs.wasNull();
    return new FeatureSnapshot(
        rs.getObject("user_id", UUID.class),
        rs.getInt("feature_schema_version"),
        instant(rs, "cutoff_w"),
        instant(rs, "feature_as_of"),
        FeatureAggregates.fromJson(Jcs.parse(rs.getString("aggregates_json"))),
        rs.getString("provenance_checksum"),
        FeatureSource.valueOf(rs.getString("source")),
        partitionNull ? null : partition,
        offsetNull ? null : offset,
        instant(rs, "computed_at"));
  }
}
