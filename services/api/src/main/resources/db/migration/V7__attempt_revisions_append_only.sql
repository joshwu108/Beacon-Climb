-- BC-0123 V7 (finding F6, ADR-0002 "attempt_revisions is append-only", threat model TM-24):
-- the runtime role beacon_app keeps SELECT and INSERT on attempt_revisions (default privileges
-- from infra/compose/postgres/init.sql) but loses UPDATE and DELETE, so the API cannot rewrite
-- or drop an audit snapshot. AttemptAuditRepository only ever inserts. The BC-0412 hard-erasure
-- job, which must delete a user's revisions, needs its own role or a later GRANT, exactly like
-- the decision ledger (V6).
--
-- ranking_policies deliberately stays writable by beacon_app: PolicyRegistry upserts the bundled
-- policy and deactivates the others at every startup as the runtime role. flyway_schema_history
-- is owned by beacon_migrate; the app role's DML on it comes from the same default privileges and
-- is left for the migrate-only service split (BEACON_FLYWAY_ENABLED, runbook).

REVOKE UPDATE, DELETE ON attempt_revisions FROM beacon_app;
