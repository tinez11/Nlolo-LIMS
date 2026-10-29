-- db-migrations/distribution/V8__agent_login.sql
-- Agent logins (2026-10-28): an agent onboarded in the register had no way to sign in -- the only agent logins were the
-- two dev ones made by hand in Keycloak. Staff now give an agent a login from the agent's page, as a client is given
-- portal access. The platform creates the Keycloak user and writes the tenant and party onto it, so the party an agent
-- is authorised as is the one on the agent's record; this table is the platform's side of that link.
--
-- One row per agent. A closed login opened again reuses the row.
--   OPEN       the agent can sign in
--   CLOSED     staff closed it
--   SUSPENDED  closed because the agent was suspended; reopened when the agent is reactivated
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

CREATE TABLE IF NOT EXISTS distribution.agent_login (
    agent_login_id    UUID PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    agent_id          UUID         NOT NULL,
    party_id          UUID         NOT NULL,
    keycloak_user_id  VARCHAR(64)  NOT NULL,
    username          VARCHAR(255) NOT NULL,
    -- How the last password reached the agent: Keycloak's emailed link, a one-time password by text, or handed over.
    delivery          VARCHAR(30)  NOT NULL CHECK (delivery IN ('EMAIL_LINK', 'SMS_PASSWORD', 'TEMPORARY_PASSWORD')),
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'SUSPENDED')),
    created_by        VARCHAR(255) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    password_sent_at  TIMESTAMPTZ  NOT NULL,
    closed_by         VARCHAR(255),
    closed_at         TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ux_agent_login_agent UNIQUE (tenant_id, agent_id),
    CONSTRAINT chk_agent_login_closed CHECK ((status = 'OPEN') = (closed_at IS NULL))
);

ALTER TABLE distribution.agent_login ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS agent_login_tenant_isolation ON distribution.agent_login;
CREATE POLICY agent_login_tenant_isolation ON distribution.agent_login
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON distribution.agent_login TO app_role;
