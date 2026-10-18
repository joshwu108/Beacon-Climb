-- BC-0310 part 2 V11: the decision ledger records which user_feature_snapshots row the ranking
-- read and, when it was the SYNC row, why the STREAM row was not used (contract 1.2.0
-- features_source on getDecision; stream design note "read path (B8)"; ADR-0004).
--
--   * feature_source is the source of the row the ranking read: 'STREAM' when the materialized
--     row existed and its cutoff_w was at least the SYNC cutoff_w (or no SYNC row existed) and
--     the stream read path was enabled (beacon.stream.enabled=true), else 'SYNC'. Together with
--     the existing feature_snapshot_user_id / feature_schema_version / feature_cutoff_w it now
--     addresses the exact row (the key gained `source` in V9).
--   * feature_fallback_reason is set only when feature_source = 'SYNC': NO_STREAM_ROW (no
--     materialized row), STREAM_OLDER_THAN_SYNC (the materialized row's cutoff_w is behind the
--     SYNC row's), STREAM_DISABLED (beacon.stream.enabled=false: the stream read path is off, so
--     a leftover STREAM row is never served). Free text is not accepted (CHECK below).
--   * Both columns are nullable: every decision written before this migration ranked on the
--     single live row, which was always SYNC (BC-0310 part 1), so NULL reads as SYNC without a
--     recorded reason. The stored record_json (decision-record.v1, additionalProperties: false)
--     is unchanged; the fallback reason lives in these columns until decision-record v2 (BC-0401).
--   * Privileges: unchanged (V6 revoked UPDATE/DELETE from beacon_app; ALTER TABLE keeps the
--     table's grants), so the ledger stays append-only for the API role.

ALTER TABLE recommendation_decisions
  ADD COLUMN feature_source text
    CONSTRAINT recommendation_decisions_feature_source_check
      CHECK (feature_source IN ('SYNC', 'STREAM')),
  ADD COLUMN feature_fallback_reason text
    CONSTRAINT recommendation_decisions_feature_fallback_reason_check
      CHECK (feature_fallback_reason IN ('NO_STREAM_ROW', 'STREAM_OLDER_THAN_SYNC', 'STREAM_DISABLED'));

ALTER TABLE recommendation_decisions
  ADD CONSTRAINT recommendation_decisions_feature_fallback_only_for_sync_check
    CHECK (feature_fallback_reason IS NULL OR feature_source = 'SYNC');

COMMENT ON COLUMN recommendation_decisions.feature_source IS
  'Source (SYNC | STREAM) of the user_feature_snapshots row the ranking read (BC-0310 part 2); NULL on rows written before V11, which read as SYNC';
COMMENT ON COLUMN recommendation_decisions.feature_fallback_reason IS
  'Why the SYNC row was served instead of the materialized STREAM row: NO_STREAM_ROW | STREAM_OLDER_THAN_SYNC | STREAM_DISABLED; NULL when the STREAM row was served or the row predates V11';
