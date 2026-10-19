-- BC-0207 V13: precomputed Model A outputs per route variant and model version.
--
--   * Written by the batch job services/ml/app/precompute.py (`make precompute-model`); the
--     API reads them (BC-0210) and never calls the model in the request path for these.
--   * One immutable row per (variant_id, model_version): the job inserts with ON CONFLICT DO
--     NOTHING, so a re-run is a no-op and a new model version gets its own rows. UPDATE is
--     revoked from beacon_app so a stored output cannot be rewritten.
--   * predicted_difficulty is on the Aurora difficulty axis like route_variants.grade_numeric;
--     embedding is the 32-d route embedding when the model kind has one (Deep Sets), else NULL.

CREATE TABLE route_model_outputs (
  variant_id            uuid NOT NULL REFERENCES route_variants (variant_id),
  model_version         text NOT NULL REFERENCES model_versions (model_version),
  predicted_difficulty  numeric(6, 3) NOT NULL,
  embedding             real[]
    CONSTRAINT route_model_outputs_embedding_dim CHECK (embedding IS NULL OR array_length(embedding, 1) = 32),
  computed_at           timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (variant_id, model_version)
);

CREATE INDEX route_model_outputs_model_idx ON route_model_outputs (model_version);

COMMENT ON TABLE route_model_outputs IS
  'Batch-precomputed Model A outputs (BC-0207): predicted difficulty and optional 32-d embedding per route variant and model version; immutable, idempotent inserts';

REVOKE UPDATE ON route_model_outputs FROM beacon_app;
