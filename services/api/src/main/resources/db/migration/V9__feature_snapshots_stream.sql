-- BC-0305 V9: the SYNC row of the attempts hook / recompute (BC-0113) and the STREAM row of the
-- climber-features.v1 materializer coexist (stream design note "Materializer (B5)", ADR-0004,
-- contracts/features/climber-features.v1.md section 6).
--
--   * The primary key grows from (user_id, feature_schema_version) to
--     (user_id, feature_schema_version, source), so one user holds at most one SYNC row and one
--     STREAM row per schema version. Every existing row is SYNC (the materializer did not exist
--     before this migration) and keeps its key unchanged.
--   * The SYNC row keeps the monotonic cutoff_w rule of BC-0124 HIGH-2
--     (FeatureSnapshotRepository.upsert); the STREAM row is guarded by the Kafka position it was
--     materialised from: apply only when the incoming offset is greater than the stored one for
--     the same partition, equal offset + same checksum is a duplicate, equal offset + different
--     checksum goes to the DLQ (MaterializerService). Never a watermark-only guard.
--   * source_partition / source_offset are the consumed Kafka position and only mean something
--     for STREAM, so the CHECK below ties their nullability to the source. The read path
--     (BC-0310 part 2) prefers the STREAM row when its cutoff_w is at least the SYNC cutoff_w.
--   * Privileges: beacon_app keeps SELECT/INSERT/UPDATE/DELETE through the default privileges of
--     infra/compose/postgres/init.sql; nothing is revoked (the materializer and the recompute both
--     update their own row, and the BC-0412 erasure job deletes).

ALTER TABLE user_feature_snapshots DROP CONSTRAINT user_feature_snapshots_pkey;
ALTER TABLE user_feature_snapshots
  ADD CONSTRAINT user_feature_snapshots_pkey PRIMARY KEY (user_id, feature_schema_version, source);

ALTER TABLE user_feature_snapshots
  ADD CONSTRAINT user_feature_snapshots_source_position_check CHECK (
    (source = 'SYNC' AND source_partition IS NULL AND source_offset IS NULL)
    OR (source = 'STREAM' AND source_partition IS NOT NULL AND source_offset IS NOT NULL)
  );

COMMENT ON TABLE user_feature_snapshots IS
  'Materialised climber-features.v1 aggregates per (user_id, feature_schema_version, source) with the explicit cutoff_w and the included feature_as_of kept apart; the SYNC row is the synchronous SQL recompute (parity oracle, monotonic in cutoff_w), the STREAM row is the materializer''s copy guarded by its Kafka (source_partition, source_offset) (ADR-0004, contracts/features/climber-features.v1.md)';
