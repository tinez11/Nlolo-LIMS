-- Module: refdata (Reference & Master Data) -- Deliverable 3 §9
-- Owns: reference_code_set. Deliberately NOT tenant-scoped -- global reference data
-- per your multi-tenancy rules (country/currency codes, TIRA codes, and every
-- configurable regulatory/operational parameter this design has externalized rather
-- than hardcoded). No RLS here for exactly that reason: there is no tenant to isolate.

CREATE SCHEMA IF NOT EXISTS refdata;

CREATE TABLE refdata.reference_code_set (
    reference_code_set_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code_set_key             VARCHAR(50) NOT NULL,
    code                       VARCHAR(50) NOT NULL,
    label                       VARCHAR(255) NOT NULL,
    value                        VARCHAR(255) NOT NULL,
    jurisdiction                  VARCHAR(10) DEFAULT 'TZ',
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_reference_code_set ON refdata.reference_code_set (code_set_key, code, jurisdiction);
CREATE INDEX idx_reference_code_set_key ON refdata.reference_code_set (code_set_key);

-- Seed data: every parameter this design has externalized across Deliverables 2-5,
-- with PLACEHOLDER values explicitly marked. These are NOT production-ready --
-- Deliverable 3 Rev 2 §13's item 4 flagged that real statutory/product values must
-- replace these before go-live; do not treat the numbers below as confirmed.
INSERT INTO refdata.reference_code_set (code_set_key, code, label, value, jurisdiction) VALUES
    ('TZ_CONTESTABILITY_MONTHS', 'DEFAULT', 'Contestability period', '24', 'TZ'),               -- PLACEHOLDER, pending C3
    ('TZ_REINSTATEMENT_WINDOW_MONTHS', 'DEFAULT', 'Reinstatement window post-lapse', '12', 'TZ'), -- PLACEHOLDER, pending B1
    ('TZ_SUSPENSION_TO_LAPSE_MONTHS', 'DEFAULT', 'Suspension-to-lapse window', '6', 'TZ'),        -- PLACEHOLDER, pending B4 follow-up (Deliverable 3 Rev 2 §13, item 1-2)
    ('OFFLINE_RECEIPT_SLA_HOURS', 'DEFAULT', 'Field receipt reconciliation SLA', '24', 'TZ');    -- PLACEHOLDER, pending operational confirmation (B4)

COMMENT ON TABLE refdata.reference_code_set IS
    'Global, non-tenant-scoped reference and regulatory parameter data. Seed values above are PLACEHOLDERS pending Legal/Compliance/Product/Actuarial sign-off (see Deliverable 3 Rev 2 §13 and Deliverable 6 covering doc §5) -- do not go live on these numbers without explicit confirmation.';

-- app_role privileges -- migrations run as the postgres superuser (scripts/migrate.sh),
-- which becomes owner of every object created above; without these explicit grants
-- app_role (the application's runtime DB role, infra/postgres/init/01-create-app-role.sql.template)
-- has no access to this schema at all and every request against it fails with
-- "permission denied for schema refdata".
GRANT USAGE ON SCHEMA refdata TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA refdata TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA refdata GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;
