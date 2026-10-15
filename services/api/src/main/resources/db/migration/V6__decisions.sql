-- BC-0111 V6: the recommendation decision ledger (ADR-0003 "Replay artifacts", planning/06
-- section 10, PRD 6.1 recommendation_decisions / recommendation_exposures; threat model TM-01,
-- TM-09, TM-24, TM-25).
--
--   * decision_id is an application-generated UUIDv7 (ADR-0002); a retried POST creates a new
--     decision on purpose (TM-09): the ranking is deterministic in (session_id, context), the
--     ledger row is not de-duplicated.
--   * record_json is the complete FullRecord of contracts/decisions/decision-record.v1.schema.json
--     exactly as validated before commit; the scalar columns beside it are the indexed / audited
--     projection of the same values (candidate_set_digest, rng_seed, environment_digest are
--     lowercase sha256 hex). request_json is the validated RecommendationRequest the GET returns.
--   * candidate_snapshot_uri is the logical path data/decisions/<2 hex>/<digest>.json of the
--     content-addressed snapshot the SnapshotStore holds; candidate_set_digest is sha256 of its
--     RFC 8785 bytes, so a replay verifies the file before parsing it (DIGEST_MISMATCH).
--   * feature_snapshot_user_id / feature_schema_version / feature_cutoff_w address the
--     user_feature_snapshots row the ranking read; feature_as_of mirrors that row (null when the
--     user had no ACTIVE attempt at or before the cutoff), while record_json and the API carry the
--     effective value (feature_as_of, else feature_cutoff_w).
--   * erased stays false until the hard-erasure job (BC-0412) rewrites the row as the ErasedStub
--     branch of the contract; that job needs its own role or a later GRANT, because:
--   * TM-24: the runtime role beacon_app keeps SELECT and INSERT (default privileges from
--     infra/compose/postgres/init.sql) but loses UPDATE and DELETE on both ledger tables, so the
--     API cannot rewrite a served decision.

CREATE TABLE recommendation_decisions (
  decision_id              uuid PRIMARY KEY,
  user_id                  uuid NOT NULL REFERENCES users (id),
  session_id               uuid NOT NULL,
  requested_at             timestamptz NOT NULL,
  policy_version           text NOT NULL REFERENCES ranking_policies (policy_version),
  policy_code_digest       char(64) NOT NULL,
  model_version            text NOT NULL,
  feature_snapshot_user_id uuid,
  feature_schema_version   int,
  feature_as_of            timestamptz,
  feature_cutoff_w         timestamptz,
  candidate_set_digest     char(64) NOT NULL,
  candidate_snapshot_uri   text NOT NULL,
  rng_seed                 char(64) NOT NULL,
  environment_digest       char(64) NOT NULL,
  request_json             jsonb NOT NULL,
  record_json              jsonb NOT NULL,
  cold_start               boolean NOT NULL,
  fallback_used            boolean NOT NULL,
  erased                   boolean NOT NULL DEFAULT false,
  created_at               timestamptz NOT NULL
);
COMMENT ON TABLE recommendation_decisions IS
  'Append-only decision ledger: one row per served recommendation set with the validated decision record (contracts/decisions/decision-record.v1.schema.json) and the digest and URI of its content-addressed candidate snapshot (ADR-0003 replay artifacts, PRD 6.1 recommendation_decisions)';

-- Owner-only listing newest first (TM-01: every read carries user_id in the predicate).
CREATE INDEX recommendation_decisions_user_idx
  ON recommendation_decisions (user_id, created_at DESC);

CREATE TABLE recommendation_exposures (
  decision_id      uuid NOT NULL REFERENCES recommendation_decisions (decision_id),
  position         int NOT NULL,
  route_variant_id text NOT NULL,
  rank_score       double precision NOT NULL,
  reason_codes_json jsonb NOT NULL,
  PRIMARY KEY (decision_id, position)
);
COMMENT ON TABLE recommendation_exposures IS
  'One row per served position of a decision: the route variant public id, its IEEE-754 rank score and its reason codes, written in the same transaction as the decision row (ADR-0003, PRD 6.1 recommendation_exposures)';

-- TM-24: the ledger is append-only for the API role.
REVOKE UPDATE, DELETE ON recommendation_decisions, recommendation_exposures FROM beacon_app;
