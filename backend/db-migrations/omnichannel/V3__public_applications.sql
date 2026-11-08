-- db-migrations/omnichannel/V3__public_applications.sql
-- Applications through the public quote link (2026-11-08, phase 2 of the user's 2026-11-02 design): somebody with no
-- login confirms their phone with a texted code, applies, and becomes a KYC-pending client with an underwriting case.
--
-- One row per application, kept as evidence of what was agreed and how: the consent wording's version and the exact
-- text shown, the language it was read in, the phone the code confirmed, and the address it came from. And the people
-- already on the books who may be the same person -- a flag for staff, never a merge (the user's decision).

CREATE TABLE IF NOT EXISTS omnichannel.public_application (
    public_application_id  UUID PRIMARY KEY,
    tenant_id              UUID          NOT NULL,
    party_id               UUID          NOT NULL,
    case_id                UUID          NOT NULL,
    phone_number           VARCHAR(20)   NOT NULL,
    consent_version        VARCHAR(40)   NOT NULL,
    consent_text           TEXT          NOT NULL,
    language               VARCHAR(2)    NOT NULL CHECK (language IN ('en', 'sw')),
    remote_address         VARCHAR(64),
    -- Comma-separated party ids of possible existing clients at the moment of applying; empty when none.
    possible_matches       TEXT          NOT NULL DEFAULT '',
    applied_at             TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ux_public_application_case UNIQUE (tenant_id, case_id)
);

CREATE INDEX IF NOT EXISTS ix_public_application_phone ON omnichannel.public_application (tenant_id, phone_number);

ALTER TABLE omnichannel.public_application ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS public_application_tenant_isolation ON omnichannel.public_application;
CREATE POLICY public_application_tenant_isolation ON omnichannel.public_application
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON omnichannel.public_application TO app_role;
