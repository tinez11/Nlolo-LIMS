-- db-migrations/organisation/V1__organisation_settings.sql
-- Organisation settings (2026-11-09, docs/superpowers/specs/2026-11-09-organisation-settings-design.md): the company's
-- own profile, its branding, the people who sign its documents, and its branches. One company per tenant; every table
-- is the tenant's own, isolated by row level security like every other module.

CREATE SCHEMA IF NOT EXISTS organisation;
GRANT USAGE ON SCHEMA organisation TO app_role;

-- Who the company is, as its documents print it. One row per tenant, written on the first save.
CREATE TABLE organisation.profile (
    tenant_id           UUID         PRIMARY KEY,
    legal_name          VARCHAR(200) NOT NULL,
    trading_name        VARCHAR(200),
    registration_number VARCHAR(60),
    tin                 VARCHAR(40),
    address             VARCHAR(400),
    phone               VARCHAR(40),
    email               VARCHAR(200),
    website             VARCHAR(200),
    tagline             VARCHAR(200),
    head_office_branch  VARCHAR(20),
    updated_by          VARCHAR(200) NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- How its documents look. Colours are #RRGGBB; the logo is a document-store reference.
CREATE TABLE organisation.branding (
    tenant_id              UUID         PRIMARY KEY,
    primary_colour         CHAR(7)      NOT NULL CHECK (primary_colour ~ '^#[0-9A-Fa-f]{6}$'),
    secondary_colour       CHAR(7)      NOT NULL CHECK (secondary_colour ~ '^#[0-9A-Fa-f]{6}$'),
    typeface               VARCHAR(10)  NOT NULL CHECK (typeface IN ('SANS', 'SERIF')),
    layout                 VARCHAR(10)  NOT NULL CHECK (layout IN ('CLASSIC', 'BANDED', 'MINIMAL')),
    paper                  VARCHAR(10)  NOT NULL CHECK (paper IN ('A4', 'LETTER')),
    footer_text            VARCHAR(300),
    report_confidentiality VARCHAR(200),
    logo_ref               VARCHAR(200),
    updated_by             VARCHAR(200) NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A person who signs documents, from a day. A document carries the signatory in force on its issue day, so a reprint
-- of an old receipt keeps the signature it was issued with. Retiring sets retired_on; a signatory is never deleted.
CREATE TABLE organisation.signatory (
    signatory_id   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL,
    name           VARCHAR(200) NOT NULL,
    title          VARCHAR(200) NOT NULL,
    signature_ref  VARCHAR(200),
    effective_from DATE         NOT NULL,
    retired_on     DATE,
    signs          TEXT[]       NOT NULL DEFAULT '{}',
    created_by     VARCHAR(200) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (retired_on IS NULL OR retired_on >= effective_from)
);
CREATE INDEX ix_signatory_tenant ON organisation.signatory (tenant_id, effective_from);

-- The company's branches: its own list, replacing the shared BRANCH reference codes for this tenant.
CREATE TABLE organisation.branch (
    tenant_id  UUID         NOT NULL,
    code       VARCHAR(20)  NOT NULL CHECK (code ~ '^[A-Z0-9_]{2,20}$'),
    name       VARCHAR(120) NOT NULL,
    address    VARCHAR(400),
    phone      VARCHAR(40),
    email      VARCHAR(200),
    manager    VARCHAR(200),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_by VARCHAR(200) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, code)
);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['profile', 'branding', 'signatory', 'branch'] LOOP
        EXECUTE format('ALTER TABLE organisation.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS %I ON organisation.%I', t || '_tenant_isolation', t);
        EXECUTE format('CREATE POLICY %I ON organisation.%I USING (tenant_id = '
            || 'NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)', t || '_tenant_isolation', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON organisation.%I TO app_role', t);
    END LOOP;
END $$;
