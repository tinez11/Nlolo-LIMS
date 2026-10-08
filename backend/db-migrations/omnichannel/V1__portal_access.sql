-- db-migrations/omnichannel/V1__portal_access.sql
-- Customer portal access (2026-10-08, the customer portal design, step 1): which client was invited to sign in, as
-- which Keycloak user, by whom, and whether the access is still open.
--
-- Staff invite a client (the user's decision D1 -- no self-registration). The platform creates the Keycloak user
-- itself and writes the tenant_id and party_id attributes onto it, so nobody types an id into Keycloak by hand: a typo
-- there would have shown one customer another's policies. This table is the platform's side of that link; the token's
-- party_id claim stays the only thing a request is authorised on.
--
-- One row per client. A revoked client invited again reuses the row: the history of who invited and revoked lives in
-- the event journal, and two rows for one client would leave "which one is the access" to whoever reads them.

CREATE SCHEMA IF NOT EXISTS omnichannel;

CREATE TABLE omnichannel.portal_access (
    portal_access_id  UUID PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    party_id          UUID         NOT NULL,
    keycloak_user_id  VARCHAR(64)  NOT NULL,
    username          VARCHAR(255) NOT NULL,
    -- How the first password reached the customer: Keycloak's emailed link, or a one-time password staff handed over.
    delivery          VARCHAR(30)  NOT NULL CHECK (delivery IN ('EMAIL_LINK', 'TEMPORARY_PASSWORD')),
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'REVOKED')),
    invited_by        VARCHAR(255) NOT NULL,
    invited_at        TIMESTAMPTZ  NOT NULL,
    activated_at      TIMESTAMPTZ,
    revoked_by        VARCHAR(255),
    revoked_at        TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ux_portal_access_party UNIQUE (tenant_id, party_id),
    CONSTRAINT chk_portal_access_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

ALTER TABLE omnichannel.portal_access ENABLE ROW LEVEL SECURITY;
CREATE POLICY portal_access_tenant_isolation ON omnichannel.portal_access
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- Migrations run as the superuser, which owns everything above; app_role, the runtime role, needs these grants.
GRANT USAGE ON SCHEMA omnichannel TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA omnichannel TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA omnichannel GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
