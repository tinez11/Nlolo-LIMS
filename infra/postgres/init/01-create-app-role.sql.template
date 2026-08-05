-- Bootstrap: create the application's runtime DB role.
--
-- CRITICAL (Deliverable 6, §1 / §5): this role must NEVER be a superuser and must
-- NEVER have BYPASSRLS -- either attribute silently voids every Row-Level Security
-- policy defined across all 16 module migrations, with no error or warning at query
-- time. This was discovered by testing RLS against the wrong role during Deliverable
-- 6's validation; NOSUPERUSER and NOBYPASSRLS below are load-bearing, not decorative.
CREATE ROLE app_role LOGIN
    NOSUPERUSER
    NOBYPASSRLS
    NOCREATEDB
    NOCREATEROLE
    PASSWORD :'app_role_password';

-- Keycloak gets its own database, separate from the application schemas, so a
-- Keycloak upgrade/migration never touches business data.
CREATE DATABASE keycloak OWNER postgres;

-- pg_partman and pg_cron are enabled here (installed at image-build time in
-- postgres/Dockerfile); registering specific tables for automated partition
-- maintenance happens LATER, in db-migrations/_post-migration/configure-pg-partman.sql,
-- run by the CD pipeline immediately after Flyway migrations -- pg_partman's
-- create_parent() requires the target table to already exist, which it doesn't yet
-- at this bootstrap stage (Flyway hasn't run).
\c lifeplatform
CREATE EXTENSION IF NOT EXISTS pg_partman SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Grants are scoped per-schema as each module's Flyway migration creates its schema
-- and tables; a placeholder default here keeps local dev usable immediately after
-- migrations run without a separate manual grant step.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
