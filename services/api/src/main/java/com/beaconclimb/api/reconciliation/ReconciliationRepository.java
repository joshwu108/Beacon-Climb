package com.beaconclimb.api.reconciliation;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.instant;
import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import com.beaconclimb.api.identity.Uuid7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code reconciliation_events} access (BC-0309, V10). The consumer inserts, the repair job reads
 * the open rows per user and closes them, the gauge counts what is open. Not gated on {@code
 * beacon.stream.enabled}: the {@code reconcile} profile runs on the host without Kafka.
 */
@Repository
public class ReconciliationRepository {

  private static final String COLUMNS =
      "id, user_id, attempt_id, attempt_revision, event_id, event_type, occurred_at,"
          + " source_watermark, reason, original_json::text AS original_json, source_partition,"
          + " source_offset, received_at, resolved_at, resolution, resolved_cutoff_w,"
          + " resolved_checksum";

  private final JdbcClient jdbc;

  public ReconciliationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts the row and returns true; returns false when a row with the same {@code event_id}
   * already exists (a redelivered reconciliation record: {@code ON CONFLICT (event_id) DO
   * NOTHING}).
   */
  public boolean insert(ReconciliationEvent event, Instant receivedAt) {
    int rows =
        jdbc.sql(
                "INSERT INTO reconciliation_events (id, user_id, attempt_id, attempt_revision,"
                    + " event_id, event_type, occurred_at, source_watermark, reason, original_json,"
                    + " source_partition, source_offset, received_at) VALUES (:id, :user_id,"
                    + " :attempt_id, :attempt_revision, :event_id, :event_type, :occurred_at,"
                    + " :source_watermark, :reason, CAST(:original_json AS jsonb), :partition,"
                    + " :offset, :received_at) ON CONFLICT (event_id) DO NOTHING")
            .param("id", Uuid7.next())
            .param("user_id", event.userId())
            .param("attempt_id", event.attemptId())
            .param("attempt_revision", event.attemptRevision())
            .param("event_id", event.eventId())
            .param("event_type", event.eventType())
            .param("occurred_at", timestamp(event.occurredAt()))
            .param("source_watermark", timestamp(event.sourceWatermark()))
            .param("reason", event.reason())
            .param("original_json", event.originalJson())
            .param("partition", event.sourcePartition())
            .param("offset", event.sourceOffset())
            .param("received_at", timestamp(receivedAt))
            .update();
    return rows == 1;
  }

  /** Users with at least one open row, in id order (the repair job's work list). */
  public List<UUID> openUserIds() {
    return jdbc.sql(
            "SELECT DISTINCT user_id FROM reconciliation_events WHERE resolved_at IS NULL"
                + " ORDER BY user_id")
        .query(UUID.class)
        .list();
  }

  /** Open rows of one user, oldest first. */
  public List<ReconciliationRow> openRowsOf(UUID userId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM reconciliation_events WHERE user_id = :user_id AND resolved_at IS NULL"
                + " ORDER BY received_at, id")
        .param("user_id", userId)
        .query(ReconciliationRepository::row)
        .list();
  }

  /** Every open row, for the gauge. */
  public long countOpen() {
    Long count =
        jdbc.sql("SELECT count(*) FROM reconciliation_events WHERE resolved_at IS NULL")
            .query(Long.class)
            .single();
    return count == null ? 0L : count;
  }

  /**
   * Closes every open row of the user with a terminal resolution and the SYNC row's cutoff and
   * checksum after the repair; returns how many rows were closed.
   */
  public int resolveOpen(
      UUID userId, Instant resolvedAt, Resolution resolution, Instant cutoffW, String checksum) {
    if (!resolution.isTerminal()) {
      throw new IllegalArgumentException(
          "resolveOpen needs a terminal resolution, not " + resolution);
    }
    return jdbc.sql(
            "UPDATE reconciliation_events SET resolved_at = :resolved_at, resolution = :resolution,"
                + " resolved_cutoff_w = :cutoff_w, resolved_checksum = :checksum"
                + " WHERE user_id = :user_id AND resolved_at IS NULL")
        .param("resolved_at", timestamp(resolvedAt))
        .param("resolution", resolution.name())
        .param("cutoff_w", timestamp(cutoffW))
        .param("checksum", checksum)
        .param("user_id", userId)
        .update();
  }

  /** Records a failed repair on every open row of the user; the rows stay open. */
  public int markError(UUID userId) {
    return jdbc.sql(
            "UPDATE reconciliation_events SET resolution = 'ERROR'"
                + " WHERE user_id = :user_id AND resolved_at IS NULL")
        .param("user_id", userId)
        .update();
  }

  public Optional<ReconciliationRow> findByEventId(UUID eventId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM reconciliation_events WHERE event_id = :event_id")
        .param("event_id", eventId)
        .query(ReconciliationRepository::row)
        .optional();
  }

  private static ReconciliationRow row(ResultSet rs, int rowNum) throws SQLException {
    int partition = rs.getInt("source_partition");
    boolean partitionNull = rs.wasNull();
    long offset = rs.getLong("source_offset");
    boolean offsetNull = rs.wasNull();
    String resolution = rs.getString("resolution");
    return new ReconciliationRow(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("attempt_id", UUID.class),
        rs.getInt("attempt_revision"),
        rs.getObject("event_id", UUID.class),
        rs.getString("event_type"),
        instant(rs, "occurred_at"),
        instant(rs, "source_watermark"),
        rs.getString("reason"),
        rs.getString("original_json"),
        partitionNull ? null : partition,
        offsetNull ? null : offset,
        instant(rs, "received_at"),
        instant(rs, "resolved_at"),
        resolution == null ? null : Resolution.valueOf(resolution),
        instant(rs, "resolved_cutoff_w"),
        rs.getString("resolved_checksum"));
  }
}
