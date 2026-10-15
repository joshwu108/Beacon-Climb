-- Runs once, as the Postgres superuser, when the data volume is first initialised
-- (docker-entrypoint-initdb.d). Creates the two least-privilege roles the API uses
-- (threat model TM-27 / FU-2):
--   beacon_migrate  owns the schema; Flyway connects as this role to apply migrations
--   beacon_app      runtime role; DML only on tables created by beacon_migrate
-- Passwords come from the container environment and must be set (psql \getenv fails
-- fast on an unset variable, so no fallback value can leak in).
\set ON_ERROR_STOP on
\getenv dbname POSTGRES_DB
\getenv migrate_password BEACON_MIGRATE_DB_PASSWORD
\getenv app_password BEACON_APP_DB_PASSWORD

CREATE ROLE beacon_migrate LOGIN PASSWORD :'migrate_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE beacon_app LOGIN PASSWORD :'app_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

REVOKE ALL ON DATABASE :"dbname" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"dbname" TO beacon_migrate, beacon_app;

-- The migration role owns the application schema; the runtime role may only use it.
ALTER SCHEMA public OWNER TO beacon_migrate;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO beacon_app;

-- Every table/sequence a future migration creates is readable and writable (DML only,
-- never DDL, TRUNCATE or ownership) by the runtime role. Individual migrations may
-- REVOKE narrower rights (e.g. no UPDATE on the decision ledger, TM-24).
ALTER DEFAULT PRIVILEGES FOR ROLE beacon_migrate IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO beacon_app;
ALTER DEFAULT PRIVILEGES FOR ROLE beacon_migrate IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO beacon_app;
