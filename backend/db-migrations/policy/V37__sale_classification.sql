-- db-migrations/policy/V37__sale_classification.sql
-- IFRS 17 I2 (classification at sale, spec §6): the facts a contract is classified by, stamped at issue and never
-- changed afterwards.
--
--   portfolio_code               the product's IFRS 17 portfolio
--   cohort_year                  the calendar year of issue (the register's COHORT election is ANNUAL)
--   profitability_bucket         the version's expected profitability, signed off by the actuary at publish
--   measurement_model_override   the version's override, or null for the accounting policy register's model
--   sales_channel, branch_code   from the case (or its defaults) at issue
--
-- The measurement model and the group of contracts are finaccounting's: it resolves them from policy.PolicyIssued
-- against the register in force on the issue date and keeps them in its own immutable policy_classification.
--
-- Nullable only for the policies issued before I2, which the one-off development backfill stamps
-- (scripts/dev/backfill-ifrs17-classification.sql). Every issue since writes all six.
ALTER TABLE policy.policy
    ADD COLUMN portfolio_code VARCHAR(10),
    ADD COLUMN cohort_year INTEGER,
    ADD COLUMN profitability_bucket VARCHAR(20),
    ADD COLUMN measurement_model_override VARCHAR(10),
    ADD COLUMN sales_channel VARCHAR(20),
    ADD COLUMN branch_code VARCHAR(10);

-- Fixed at sale. A value once written is never changed; null -> value is allowed once, for the backfill.
CREATE OR REPLACE FUNCTION policy.guard_sale_classification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.portfolio_code IS NOT NULL AND NEW.portfolio_code IS DISTINCT FROM OLD.portfolio_code)
       OR (OLD.cohort_year IS NOT NULL AND NEW.cohort_year IS DISTINCT FROM OLD.cohort_year)
       OR (OLD.profitability_bucket IS NOT NULL AND NEW.profitability_bucket IS DISTINCT FROM OLD.profitability_bucket)
       OR (OLD.measurement_model_override IS NOT NULL
           AND NEW.measurement_model_override IS DISTINCT FROM OLD.measurement_model_override)
       OR (OLD.sales_channel IS NOT NULL AND NEW.sales_channel IS DISTINCT FROM OLD.sales_channel)
       OR (OLD.branch_code IS NOT NULL AND NEW.branch_code IS DISTINCT FROM OLD.branch_code) THEN
        RAISE EXCEPTION 'POLICY_CLASSIFICATION_IMMUTABLE: policy % was classified at sale', OLD.policy_number;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_guard_sale_classification BEFORE UPDATE ON policy.policy
    FOR EACH ROW EXECUTE FUNCTION policy.guard_sale_classification();
