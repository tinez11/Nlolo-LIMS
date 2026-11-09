-- db-migrations/reporting/V3__solvency_factor_set.sql
-- The solvency factors (2026-11-09, the reports redesign batch 3, decision D5): risk charges, thresholds and stress
-- shocks, as versions. A version is never changed once published -- a past month is re-run on the version in force
-- then -- so there is no UPDATE grant. A company with no row here is on the built-in placeholder set (version 0).
--
-- The factors are one JSON document: the shape is the application's (reporting.api.SolvencyFactors), and nothing
-- queries inside it.

CREATE TABLE IF NOT EXISTS reporting.solvency_factor_set (
    tenant_id      UUID          NOT NULL,
    version        INTEGER       NOT NULL CHECK (version > 0),
    name           VARCHAR(200)  NOT NULL,
    effective_from DATE          NOT NULL,
    placeholder    BOOLEAN       NOT NULL,
    factors        JSONB         NOT NULL,
    created_by     VARCHAR(200)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, version)
);

ALTER TABLE reporting.solvency_factor_set ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS solvency_factor_set_tenant_isolation ON reporting.solvency_factor_set;
CREATE POLICY solvency_factor_set_tenant_isolation ON reporting.solvency_factor_set
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON reporting.solvency_factor_set TO app_role;
