-- BC-0107 V2: opaque bearer sessions (ADR-0005, planning/06 section 6, threat model TM-04/TM-21).
--
--   * token_hash is the lowercase sha256 hex of the 256-bit random token; the token itself is never
--     stored. A lookup by hash is the primary-key path.
--   * expires_at is created_at + 24 h; revoked_at is set on logout, password change and erasure.
--   * The application role beacon_app gets SELECT/INSERT/UPDATE/DELETE through the default
--     privileges in infra/compose/postgres/init.sql; no GRANT is needed here.
CREATE TABLE sessions (
  token_hash char(64) PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES users (id),
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);
COMMENT ON TABLE sessions IS
  'Opaque bearer sessions stored as sha256(token) with 24 h expiry and revocation (ADR-0005, TM-21)';

-- Revocation of every session of a user (password change, erasure) and per-user listings.
CREATE INDEX sessions_user_id_idx ON sessions (user_id);
