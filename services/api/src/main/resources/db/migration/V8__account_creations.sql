-- BC-0123c V8 (finding F5 residual, threat model TM-22, TM-25): one row per successfully
-- created account with the client address that created it, so AccountCreationCap can count in
-- SQL how many accounts one address has created since midnight UTC and refuse the next one with
-- 429 RATE_LIMITED once beacon.auth.register-per-ip-per-day is reached. Counting in the
-- database rather than in memory survives restarts and is shared by every api process.
--
--   * client_ip is the address ClientAddress resolved for the request (the socket peer, or the
--     first X-Forwarded-For entry when the peer is one of beacon.http.trusted-proxies). It is
--     text, not inet, so an unusual but trusted value (an IPv6 zone id, say) can never turn a
--     registration into a 500.
--   * created_at is the application clock (java.time.Clock), the same instant the session row
--     gets, so a pinned test clock and the production UTC clock count the same day boundary.
--   * Retention: only the current UTC day is ever read ((client_ip, created_at) index). Rows
--     older than two days may be deleted by a future cleanup job; the runtime role keeps DELETE
--     for that. Until one exists the table grows by one small row per registration.
--   * The application role beacon_app gets SELECT/INSERT/UPDATE/DELETE through the default
--     privileges in infra/compose/postgres/init.sql; no GRANT is needed here. UPDATE is revoked
--     below: the counter is append-only (V6 and V7 do the same for the ledger and audit tables).

CREATE TABLE account_creations (
  user_id    uuid PRIMARY KEY REFERENCES users (id),
  client_ip  text NOT NULL,
  created_at timestamptz NOT NULL
);
COMMENT ON TABLE account_creations IS
  'One row per created account with the client address that registered it; AccountCreationCap counts rows per address since midnight UTC to enforce beacon.auth.register-per-ip-per-day (BC-0123 F5 residual, TM-22, TM-25). Only the current UTC day is read; older rows may be purged.';

CREATE INDEX account_creations_ip_idx ON account_creations (client_ip, created_at);

REVOKE UPDATE ON account_creations FROM beacon_app;
