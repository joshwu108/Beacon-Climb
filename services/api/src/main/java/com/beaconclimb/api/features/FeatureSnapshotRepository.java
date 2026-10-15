package com.beaconclimb.api.features;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.instant;
import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import com.beaconclimb.api.platform.json.Jcs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code user_feature_snapshots} access: a monotonic upsert on the primary key and a read.
 *
 * <p>Monotonic rule (BC-0124 HIGH-2): the live row of a {@code (user_id, feature_schema_version)}
 * only ever moves forward in {@code cutoff_w}. An upsert whose {@code cutoff_w} is older than the
 * stored one is a no-op ({@code ON CONFLICT ... DO UPDATE ... WHERE stored.cutoff_w <=
 * EXCLUDED.cutoff_w}), so a recompute at an explicit past cutoff can never replace the snapshot the
 * attempts hook just wrote at {@code W = now} and silently turn a warm user cold. An equal cutoff
 * does replace, which is what a repeat recompute after a correction relies on.
 */
@Repository
public class FeatureSnapshotRepository {

  private final JdbcClient jdbc;

  public FeatureSnapshotRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts or replaces the live row and returns true; returns false (and leaves the row alone)
   * when the stored {@code cutoff_w} is newer than {@code snapshot.cutoffW()}.
   */
  public boolean upsert(FeatureSnapshot snapshot) {
    int rows =
        jdbc.sql(
                "INSERT INTO user_feature_snapshots (user_id, feature_schema_version, cutoff_w,"
                    + " feature_as_of, aggregates_json, provenance_checksum, source,"
                    + " source_partition, source_offset, computed_at) VALUES (:user_id, :version,"
                    + " :cutoff_w, :as_of, CAST(:aggregates AS jsonb), :checksum, :source,"
                    + " :partition, :offset, :computed_at)"
                    + " ON CONFLICT (user_id, feature_schema_version) DO UPDATE SET"
                    + " cutoff_w = EXCLUDED.cutoff_w, feature_as_of = EXCLUDED.feature_as_of,"
                    + " aggregates_json = EXCLUDED.aggregates_json,"
                    + " provenance_checksum = EXCLUDED.provenance_checksum,"
                    + " source = EXCLUDED.source, source_partition = EXCLUDED.source_partition,"
                    + " source_offset = EXCLUDED.source_offset, computed_at = EXCLUDED.computed_at"
                    + " WHERE user_feature_snapshots.cutoff_w <= EXCLUDED.cutoff_w")
            .param("user_id", snapshot.userId())
            .param("version", snapshot.featureSchemaVersion())
            .param("cutoff_w", timestamp(snapshot.cutoffW()))
            .param("as_of", timestamp(snapshot.featureAsOf()))
            .param("aggregates", snapshot.aggregates().toJson())
            .param("checksum", snapshot.provenanceChecksum())
            .param("source", snapshot.source().name())
            .param("partition", snapshot.sourcePartition())
            .param("offset", snapshot.sourceOffset())
            .param("computed_at", timestamp(snapshot.computedAt()))
            .update();
    return rows == 1;
  }

  public Optional<FeatureSnapshot> find(UUID userId, int featureSchemaVersion) {
    return jdbc.sql(
            "SELECT user_id, feature_schema_version, cutoff_w, feature_as_of,"
                + " aggregates_json::text AS aggregates_json, provenance_checksum, source,"
                + " source_partition, source_offset, computed_at FROM user_feature_snapshots"
                + " WHERE user_id = :user_id AND feature_schema_version = :version")
        .param("user_id", userId)
        .param("version", featureSchemaVersion)
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
