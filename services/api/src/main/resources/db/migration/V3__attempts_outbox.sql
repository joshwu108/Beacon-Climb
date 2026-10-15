-- BC-0109 V3: attempts, per-revision audit and the transactional event outbox (PRD 6.1,
-- ADR-0002 "Idempotent create", "Revisions and audit", "Transactional outbox and events";
-- planning/06-first-two-sprints.md section 7; threat model TM-02, TM-06, TM-08, TM-10, TM-11).
--
--   * attempt_id is an application-generated UUIDv7 (ADR-0002); no DB default.
--   * (user_id, submission_idempotency_key) is unique: the application inserts with
--     ON CONFLICT DO NOTHING and re-reads the committed row, so twenty identical concurrent
--     POSTs produce one row and one outbox event (BC-0109-CONCURRENT-20).
--   * request_fingerprint is the sha256 hex of the RFC 8785 canonical request body
--     (BC-0109-JCS); a replay with the same key and a different fingerprint is 409.
--   * response_body_json is the logical Attempt body (without the route summary, which is
--     joined on read) persisted in the same transaction so a replay returns it verbatim.
--   * route_variant_public_id denormalises "<provider_route_uuid>:<angle_deg>" so the event
--     payload and the stored body never need a catalog join.
--   * notes is owner-only (contract A2 / C-05): never copied to attempt_event_outbox.
--   * The application role beacon_app gets SELECT/INSERT/UPDATE/DELETE through the default
--     privileges in infra/compose/postgres/init.sql; no GRANT is needed here.

CREATE TABLE attempts (
  attempt_id                 uuid PRIMARY KEY,
  user_id                    uuid NOT NULL REFERENCES users (id),
  variant_id                 uuid NOT NULL REFERENCES route_variants (variant_id),
  route_variant_public_id    text NOT NULL,
  occurred_at                timestamptz NOT NULL,
  received_at                timestamptz NOT NULL,
  attempts_count             int NOT NULL CHECK (attempts_count >= 1),
  sent                       boolean NOT NULL,
  completion_fraction        numeric(4, 3) CHECK (completion_fraction BETWEEN 0 AND 1),
  perceived_effort           smallint CHECK (perceived_effort BETWEEN 1 AND 10),
  notes                      text CHECK (char_length(notes) <= 500),
  revision                   int NOT NULL DEFAULT 1 CHECK (revision >= 1),
  status                     text NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE', 'RETRACTED')),
  submission_idempotency_key text NOT NULL,
  request_fingerprint        char(64) NOT NULL,
  response_body_json         jsonb NOT NULL,
  updated_at                 timestamptz NOT NULL,
  UNIQUE (user_id, submission_idempotency_key)
);
COMMENT ON TABLE attempts IS
  'Canonical attempt log, one row per attempt at its latest revision, idempotent on (user_id, submission_idempotency_key) with the JCS request fingerprint and the persisted 201 body (PRD 6.1 attempts, ADR-0002 idempotent create)';

-- History listing: newest first by occurred_at then attempt_id, keyset-paged (contract A11).
CREATE INDEX attempts_user_history_idx
  ON attempts (user_id, occurred_at DESC, attempt_id DESC);

-- Feature recompute and per-variant cooldown (BC-0113, BC-0111).
CREATE INDEX attempts_user_variant_idx
  ON attempts (user_id, variant_id, occurred_at DESC);

CREATE TABLE attempt_revisions (
  attempt_id    uuid NOT NULL REFERENCES attempts (attempt_id),
  revision      int NOT NULL,
  snapshot_json jsonb NOT NULL,
  changed_at    timestamptz NOT NULL,
  change_type   text NOT NULL
                CHECK (change_type IN ('RECORDED', 'CORRECTED', 'RETRACTED')),
  PRIMARY KEY (attempt_id, revision)
);
COMMENT ON TABLE attempt_revisions IS
  'Immutable audit copy of the Attempt body (without route summary) at every revision; written in the same transaction as the attempts row (ADR-0002 revisions and audit)';

CREATE TABLE attempt_event_outbox (
  event_id           uuid PRIMARY KEY,
  aggregate_id       uuid NOT NULL,
  aggregate_revision int NOT NULL,
  event_type         text NOT NULL,
  schema_version     int NOT NULL,
  payload_json       jsonb NOT NULL,
  created_at         timestamptz NOT NULL,
  published_at       timestamptz,
  UNIQUE (aggregate_id, aggregate_revision)
);
COMMENT ON TABLE attempt_event_outbox IS
  'Transactional outbox for attempts.v1 events: one row per (attempt_id, revision), payload validated against contracts/events/attempts.v1.schema.json, published_at set by the BC-0301 publisher (ADR-0002 transactional outbox)';

-- The publisher polls unpublished rows oldest first (FOR UPDATE SKIP LOCKED, BC-0301).
CREATE INDEX attempt_event_outbox_unpublished_idx
  ON attempt_event_outbox (created_at)
  WHERE published_at IS NULL;
