package com.beaconclimb.api.outbox;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.instant;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL of the outbox publisher and its reconciliation view (ADR-0002 "Transactional outbox"). Every
 * read of the backlog goes through the partial index {@code attempt_event_outbox_unpublished_idx}
 * (created_at WHERE published_at IS NULL, V3), so its cost grows with the backlog, not the table.
 * {@code make outbox-status} runs the same aggregate through psql.
 */
public class OutboxRepository {

  /** Backlog summary: rows unpublished, age of the oldest one (0 when empty), rows past stale. */
  public record Backlog(long rows, double ageSeconds, long staleRows) {}

  /** One row unpublished for longer than {@code stale-after}. */
  public record StaleRow(
      UUID eventId,
      UUID aggregateId,
      int aggregateRevision,
      String eventType,
      java.time.Instant createdAt,
      double ageSeconds) {}

  private static final String UNPUBLISHED = " FROM attempt_event_outbox WHERE published_at IS NULL";

  private final JdbcClient jdbc;

  public OutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Oldest unpublished rows first, locked for the calling transaction; rows another publisher holds
   * are skipped rather than waited for.
   */
  public List<OutboxRow> lockUnpublished(int limit) {
    return jdbc.sql(
            "SELECT event_id, aggregate_id, aggregate_revision, event_type, schema_version,"
                + " payload_json->>'user_id' AS user_id, payload_json::text AS payload_json,"
                + " created_at"
                + UNPUBLISHED
                + " ORDER BY created_at, event_id LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("limit", limit)
        .query(
            (rs, i) ->
                new OutboxRow(
                    rs.getObject("event_id", UUID.class),
                    rs.getObject("aggregate_id", UUID.class),
                    rs.getInt("aggregate_revision"),
                    rs.getString("event_type"),
                    rs.getInt("schema_version"),
                    rs.getString("user_id"),
                    rs.getString("payload_json"),
                    instant(rs, "created_at")))
        .list();
  }

  /** Marks the rows of a fully acknowledged batch; the caller's transaction commits it. */
  public int markPublished(List<UUID> eventIds) {
    if (eventIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            "UPDATE attempt_event_outbox SET published_at = now() WHERE event_id IN (:ids)"
                + " AND published_at IS NULL")
        .param("ids", eventIds)
        .update();
  }

  /** One aggregate over the unpublished rows (the gauge refresh after every poll). */
  public Backlog backlog(Duration staleAfter) {
    return jdbc.sql(
            "SELECT count(*) AS rows,"
                + " coalesce(extract(epoch FROM (now() - min(created_at))), 0) AS age_seconds,"
                + " count(*) FILTER (WHERE created_at < now() - make_interval(secs => :stale))"
                + " AS stale_rows"
                + UNPUBLISHED)
        .param("stale", seconds(staleAfter))
        .query(
            (rs, i) ->
                new Backlog(
                    rs.getLong("rows"), rs.getDouble("age_seconds"), rs.getLong("stale_rows")))
        .single();
  }

  /** The stale rows themselves, oldest first, for {@link OutboxReconciliation}. */
  public List<StaleRow> stale(Duration staleAfter, int limit) {
    return jdbc.sql(
            "SELECT event_id, aggregate_id, aggregate_revision, event_type, created_at,"
                + " extract(epoch FROM (now() - created_at)) AS age_seconds"
                + UNPUBLISHED
                + " AND created_at < now() - make_interval(secs => :stale)"
                + " ORDER BY created_at, event_id LIMIT :limit")
        .param("stale", seconds(staleAfter))
        .param("limit", limit)
        .query(
            (rs, i) ->
                new StaleRow(
                    rs.getObject("event_id", UUID.class),
                    rs.getObject("aggregate_id", UUID.class),
                    rs.getInt("aggregate_revision"),
                    rs.getString("event_type"),
                    instant(rs, "created_at"),
                    rs.getDouble("age_seconds")))
        .list();
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }
}
