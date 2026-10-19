-- BC-0205 V12: registry of packaged models (contracts/ml/model-manifest.v1.schema.json).
--
--   * One row per immutable model_version. The ML registry CLI (services/ml/app/register.py)
--     inserts the row after beacon_ml.registry.verify: READY when every file hash, the artifact
--     digest and the fixture predictions match, REJECTED (with rejection_reason) otherwise.
--   * Activation is explicit: at most one active version per family (partial unique index). A
--     REJECTED version can never be active (CHECK), so a bad artifact never displaces the
--     previously active one.
--   * license_review_status records the data rights of the training set; LOCAL_RESEARCH_ONLY
--     models are never published (docs/data-source-audit.md section 7).
--   * Privileges: beacon_app gets DML through the default privileges in init.sql; DELETE is
--     revoked so the history of registrations is kept.

CREATE TABLE model_versions (
  model_version          text PRIMARY KEY
    CONSTRAINT model_versions_version_format CHECK (model_version ~ '^[a-z0-9][a-z0-9.-]{2,63}$'),
  family                 text NOT NULL CONSTRAINT model_versions_family_check CHECK (family IN ('model-a')),
  kind                   text NOT NULL CONSTRAINT model_versions_kind_check CHECK (kind IN ('sklearn-hgb', 'torch-deepsets')),
  artifact_digest        char(64) NOT NULL,
  input_schema_version   text NOT NULL,
  training_manifest_id   text NOT NULL,
  training_run_id        text NOT NULL,
  metrics_ref            text NOT NULL,
  license_review_status  text NOT NULL
    CONSTRAINT model_versions_license_check CHECK (license_review_status IN ('LOCAL_RESEARCH_ONLY', 'SYNTHETIC')),
  status                 text NOT NULL CONSTRAINT model_versions_status_check CHECK (status IN ('READY', 'REJECTED', 'RETIRED')),
  rejection_reason       text,
  active                 boolean NOT NULL DEFAULT false,
  registered_at          timestamptz NOT NULL DEFAULT now(),
  activated_at           timestamptz,
  CONSTRAINT model_versions_reason_only_when_rejected CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
  CONSTRAINT model_versions_only_ready_is_active CHECK (NOT active OR status = 'READY')
);

CREATE UNIQUE INDEX model_versions_one_active_per_family ON model_versions (family) WHERE active;

COMMENT ON TABLE model_versions IS
  'Packaged model versions (BC-0205): READY or REJECTED by load-time validation of hashes and fixture predictions; at most one active READY version per family';

REVOKE DELETE ON model_versions FROM beacon_app;
