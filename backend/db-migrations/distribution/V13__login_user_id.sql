-- db-migrations/distribution/V13__login_user_id.sql
-- The sign-in system's id for the agent's login was kept in keycloak_user_id, named for Keycloak. Since the Authentik cut-over
-- (2026-11-19) it holds Authentik's id, and Keycloak is gone (2026-11-20, ticket 11 of .scratch/authentik-cutover), so
-- the column is named for what it is. Guarded, so a database renamed by hand is left as it is.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'distribution' AND table_name = 'agent_login' AND column_name = 'keycloak_user_id') THEN
        ALTER TABLE distribution.agent_login RENAME COLUMN keycloak_user_id TO login_user_id;
    END IF;
END $$;
