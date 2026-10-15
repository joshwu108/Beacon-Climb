package com.beaconclimb.api.attempts;

import static com.beaconclimb.api.platform.jdbc.JdbcTypes.timestamp;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only writes that accompany every accepted attempt change in the same transaction
 * (ADR-0002): the immutable {@code attempt_revisions} snapshot and the {@code attempt_event_outbox}
 * row. Both are keyed by {@code (attempt_id, revision)}, so a change can never produce two audit
 * rows or two events.
 */
@Repository
public class AttemptAuditRepository {

  public static final int SCHEMA_VERSION = 1;

  private final JdbcClient jdbc;

  public AttemptAuditRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insertRevision(
      UUID attemptId, int revision, String snapshotJson, Instant changedAt, AttemptChange change) {
    jdbc.sql(
            "INSERT INTO attempt_revisions (attempt_id, revision, snapshot_json, changed_at,"
                + " change_type) VALUES (:id, :revision, CAST(:snapshot AS jsonb), :changed_at,"
                + " :change_type)")
        .param("id", attemptId)
        .param("revision", revision)
        .param("snapshot", snapshotJson)
        .param("changed_at", timestamp(changedAt))
        .param("change_type", change.name())
        .update();
  }

  public void insertEvent(
      UUID eventId,
      UUID attemptId,
      int revision,
      AttemptChange change,
      String payloadJson,
      Instant createdAt) {
    jdbc.sql(
            "INSERT INTO attempt_event_outbox (event_id, aggregate_id, aggregate_revision,"
                + " event_type, schema_version, payload_json, created_at) VALUES (:event_id, :id,"
                + " :revision, :event_type, :schema_version, CAST(:payload AS jsonb), :created_at)")
        .param("event_id", eventId)
        .param("id", attemptId)
        .param("revision", revision)
        .param("event_type", change.eventType())
        .param("schema_version", SCHEMA_VERSION)
        .param("payload", payloadJson)
        .param("created_at", timestamp(createdAt))
        .update();
  }
}
