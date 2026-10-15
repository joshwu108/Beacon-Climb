-- BC-0113 V4: materialised per-user climber features (planning/06 section 8,
-- contracts/features/climber-features.v1.md, ADR-0004 "Testable invariant").
--
--   * One row per (user_id, feature_schema_version). The synchronous recompute (source SYNC)
--     upserts it after every attempt write and on `make recompute-features`; the streaming
--     materializer (BC-0305, source STREAM) upserts the same key from climber-features.v1
--     events and records the Kafka partition/offset it consumed.
--   * cutoff_w is the explicit cutoff W the recompute was asked for; feature_as_of is the
--     max occurred_at actually included (<= W, null when nothing was included). Parity
--     (BC-0306) keys on cutoff_w; the API shows feature_as_of (ADR-0004).
--   * provenance_checksum is sha256 over the sorted "<attempt_id>:<revision>" pairs of the
--     included attempts, so stream and batch can be compared without comparing payloads.
--   * aggregates_json holds exactly the climber-features.v1 aggregate keys.

CREATE TABLE user_feature_snapshots (
  user_id                uuid NOT NULL REFERENCES users (id),
  feature_schema_version int NOT NULL,
  cutoff_w               timestamptz NOT NULL,
  feature_as_of          timestamptz,
  aggregates_json        jsonb NOT NULL,
  provenance_checksum    char(64) NOT NULL,
  source                 text NOT NULL CHECK (source IN ('SYNC', 'STREAM')),
  source_partition       int,
  source_offset          bigint,
  computed_at            timestamptz NOT NULL,
  PRIMARY KEY (user_id, feature_schema_version)
);
COMMENT ON TABLE user_feature_snapshots IS
  'Materialised climber-features.v1 aggregates per (user_id, feature_schema_version) with the explicit cutoff_w and the included feature_as_of kept apart; the synchronous SQL recompute is the parity oracle for the stream materializer (ADR-0004, contracts/features/climber-features.v1.md)';
