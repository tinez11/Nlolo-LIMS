-- db-migrations/finaccounting/V11__groups_and_policy_classification.sql
-- IFRS 17 I2 (classification at sale, spec §6 and §7.2).
--
-- 1. group_of_contracts becomes the IFRS 17 unit of account it was named for: one row per portfolio x measurement
--    model x annual cohort x expected profitability, keyed for people as e.g. END-GMM-2026-REM. The model is in the
--    key because a group has one measurement model and two contracts of the same portfolio, cohort and bucket can
--    differ (a version's override, or a register change in the year). The M1 table was never written, so its rows
--    (none) are cleared and the product link dropped: a group spans products.
--
-- 2. policy_classification is finaccounting's own copy of how each contract is classified, built from
--    policy.PolicyIssued (and, for a vesting pension, policy.AnnuityVested): the facts sold (portfolio, cohort,
--    bucket, channel, branch) plus what finaccounting decides -- the measurement model under the accounting policy
--    register in force on the date, why (REGISTER, an allowed OVERRIDE, or an OVERRIDE_REFUSED by the register),
--    and the group. Posting reads its dimensions from here and never calls another module. Rows are never changed:
--    a vesting pension gets a SECOND row (reason VESTING), a new contract in a new cohort (G-08).

ALTER TABLE finaccounting.group_of_contracts
    DROP CONSTRAINT IF EXISTS group_of_contracts_measurement_model_check,
    ALTER COLUMN product_id DROP NOT NULL,
    ADD COLUMN group_key VARCHAR(40),
    ADD COLUMN portfolio_code VARCHAR(10),
    ADD COLUMN profitability_bucket VARCHAR(20);

DELETE FROM finaccounting.group_of_contracts;   -- the M1 placeholder was never written

ALTER TABLE finaccounting.group_of_contracts
    ALTER COLUMN group_key SET NOT NULL,
    ALTER COLUMN portfolio_code SET NOT NULL,
    ALTER COLUMN profitability_bucket SET NOT NULL,
    ADD CONSTRAINT group_of_contracts_model_check CHECK (measurement_model IN ('GMM','VFA','PAA','IFRS9')),
    ADD CONSTRAINT group_of_contracts_bucket_check
        CHECK (profitability_bucket IN ('ONEROUS','NO_SIGNIFICANT_RISK','REMAINING'));
CREATE UNIQUE INDEX ux_group_of_contracts_key ON finaccounting.group_of_contracts (tenant_id, group_key);

CREATE TABLE finaccounting.policy_classification (
    tenant_id             UUID NOT NULL,
    policy_number         VARCHAR(20) NOT NULL,
    reason                VARCHAR(10) NOT NULL CHECK (reason IN ('ISSUE','VESTING')),
    effective_from        DATE NOT NULL,
    group_id              UUID NOT NULL REFERENCES finaccounting.group_of_contracts (group_id),
    group_key             VARCHAR(40) NOT NULL,
    measurement_model     VARCHAR(10) NOT NULL CHECK (measurement_model IN ('GMM','VFA','PAA','IFRS9')),
    model_basis           VARCHAR(20) NOT NULL CHECK (model_basis IN ('REGISTER','OVERRIDE','OVERRIDE_REFUSED')),
    register_version      INTEGER NOT NULL,
    portfolio_code        VARCHAR(10) NOT NULL,
    cohort_year           INTEGER NOT NULL,
    profitability_bucket  VARCHAR(20) NOT NULL,
    requested_override    VARCHAR(10),
    product_id            UUID,
    product_version_id    UUID,
    sales_channel         VARCHAR(20),
    branch_code           VARCHAR(10),
    classified_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, policy_number, reason)
);
CREATE INDEX idx_policy_classification_group ON finaccounting.policy_classification (tenant_id, group_id);

ALTER TABLE finaccounting.policy_classification ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_classification_tenant_isolation ON finaccounting.policy_classification
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON finaccounting.policy_classification TO app_role;
GRANT SELECT, INSERT ON finaccounting.group_of_contracts TO app_role;

-- Classified once, never changed (spec §6). Binds every role, the owner included.
CREATE OR REPLACE FUNCTION finaccounting.refuse_classification_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'CLASSIFICATION_IMMUTABLE: a policy''s IFRS 17 classification is never changed; a vesting pension is classified again as a new contract';
END $$;
CREATE TRIGGER trg_refuse_classification_change BEFORE UPDATE OR DELETE ON finaccounting.policy_classification
    FOR EACH ROW EXECUTE FUNCTION finaccounting.refuse_classification_change();
