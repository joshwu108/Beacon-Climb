-- BC-0309 V10: the reconciliation table for attempts.v1 events the stream job could not apply
-- because they arrived more than 24 h behind its watermark (ADR-0004 "Events beyond 24 h enter
-- reconciliation", contracts/features/climber-features.v1.md section 10 "Late events", stream
-- design note "Reconciliation (B6)").
--
--   * One row per late event, keyed by the event's own event_id (UNIQUE): the consumer of
--     stream-reconciliation.v1 inserts with ON CONFLICT (event_id) DO NOTHING, so a redelivered
--     reconciliation record is a no-op (at-least-once delivery, idempotent sink).
--   * original_json is the ORIGINAL attempts.v1 payload exactly as the stream job forwarded it
--     (the schema has no notes member and the shared validator rejects one before the insert, so
--     free text never lands here). source_watermark is the job's watermark when it judged the
--     event late (header watermark=<W>); reason is the header reason (LATE_BEYOND_WINDOW) or
--     OTHER when a record arrives without one.
--   * source_partition / source_offset are the stream-reconciliation.v1 position the row was
--     consumed from (diagnostics only; the event_id is the idempotency key).
--   * resolved_at / resolution are set by the repair job (`make reconcile`, profile reconcile):
--     RECONCILED when the SYNC snapshot changed, ALREADY_CURRENT when the recompute produced the
--     checksum the SYNC row already had (the usual case: the api's own attempts hook recomputes
--     from Postgres, so a late event is only "late" for the STREAM row). resolved_cutoff_w and
--     resolved_checksum are the W and provenance_checksum of the SYNC row after the repair. A
--     failed repair records resolution = ERROR but leaves resolved_at NULL, so the row stays open
--     and the next run retries it; the CHECK below ties the three states together.
--   * ON DELETE CASCADE from users: erasure (BC-0412) must not leave event payloads behind.
--   * Privileges: beacon_app gets SELECT/INSERT/UPDATE/DELETE through the default privileges of
--     infra/compose/postgres/init.sql (the consumer inserts, the repair job updates, erasure
--     deletes); nothing is revoked.

CREATE TABLE reconciliation_events (
  id                 uuid PRIMARY KEY,
  user_id            uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  attempt_id         uuid NOT NULL,
  attempt_revision   int NOT NULL CHECK (attempt_revision >= 1),
  event_id           uuid NOT NULL UNIQUE,
  event_type         text NOT NULL
                     CHECK (event_type IN ('ATTEMPT_RECORDED', 'ATTEMPT_CORRECTED', 'ATTEMPT_RETRACTED')),
  occurred_at        timestamptz NOT NULL,
  source_watermark   timestamptz,
  reason             text NOT NULL CHECK (reason IN ('LATE_BEYOND_WINDOW', 'OTHER')),
  original_json      jsonb NOT NULL,
  source_partition   int,
  source_offset      bigint,
  received_at        timestamptz NOT NULL DEFAULT now(),
  resolved_at        timestamptz,
  resolution         text CHECK (resolution IN ('RECONCILED', 'ALREADY_CURRENT', 'ERROR')),
  resolved_cutoff_w  timestamptz,
  resolved_checksum  char(64),
  CONSTRAINT reconciliation_events_resolution_state_check CHECK (
    (resolved_at IS NULL AND (resolution IS NULL OR resolution = 'ERROR')
      AND resolved_cutoff_w IS NULL AND resolved_checksum IS NULL)
    OR (resolved_at IS NOT NULL AND resolution IN ('RECONCILED', 'ALREADY_CURRENT')
      AND resolved_cutoff_w IS NOT NULL AND resolved_checksum IS NOT NULL)
  )
);

-- The repair job's work list and the open-rows gauge: only unresolved rows, in arrival order.
CREATE INDEX reconciliation_events_open_idx
  ON reconciliation_events (received_at) WHERE resolved_at IS NULL;
-- Per-user lookups (the repair job resolves a user's rows together; erasure cascades by user).
CREATE INDEX reconciliation_events_user_idx ON reconciliation_events (user_id);

COMMENT ON TABLE reconciliation_events IS
  'attempts.v1 events the stream job judged late beyond its 24 h window (stream-reconciliation.v1, original payload kept verbatim, one row per event_id); the reconcile profile recomputes the SYNC snapshot of each affected user at W = now and marks the rows RECONCILED or ALREADY_CURRENT (ADR-0004, BC-0309)';
