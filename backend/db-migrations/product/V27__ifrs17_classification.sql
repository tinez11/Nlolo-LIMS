-- db-migrations/product/V27__ifrs17_classification.sql
-- IFRS 17 I2 (classification at sale, spec §6): what a product contributes to a contract's IFRS 17 classification.
--
-- 1. product_definition.portfolio_code -- the IFRS 17 portfolio (contracts subject to similar risks, managed
--    together). Mandatory: a policy's group is portfolio + cohort + expected profitability.
-- 2. product_version.expected_profitability_bucket -- what the actuary signs off at publish (IFRS 17 para 16).
-- 3. product_version.measurement_model_override -- a version's override, which counts only where the accounting
--    policy register allows it for the portfolio. The register (finaccounting) decides the model.
-- 4. product_version.ifrs_measurement_model is RETIRED, not repurposed: GMM/PAA chosen at publish, by habit, before
--    there was a register. Read as an override it would contradict the register for every unit-linked version that
--    stored GMM. It becomes nullable and is no longer asked for.

ALTER TABLE product.product_definition ADD COLUMN portfolio_code VARCHAR(10)
    CHECK (portfolio_code IN ('TERM','WL','END','MB','PAR','ULIP','SAV','DEP','IANN','DANN','PEN','GRPL','CRL','FUN'));

-- Existing products take their category's portfolio. Only the category: this migration reads nothing a partial
-- schema may lack. The finer reading -- a product whose versions make it with-profits, a pension, a deposit, a
-- savings account or money-back -- is the one-off development backfill (scripts/dev/backfill-ifrs17-classification.sql);
-- production has no products yet.
UPDATE product.product_definition d SET portfolio_code = CASE d.category
    WHEN 'TERM_LIFE' THEN 'TERM' WHEN 'ENDOWMENT' THEN 'END' WHEN 'WHOLE_LIFE' THEN 'WL'
    WHEN 'ANNUITY' THEN 'IANN' WHEN 'UNIT_LINKED' THEN 'ULIP' WHEN 'GROUP_LIFE' THEN 'GRPL'
    WHEN 'EDUCATION_SAVINGS' THEN 'END' WHEN 'CREDIT_LIFE' THEN 'CRL' WHEN 'FUNERAL' THEN 'FUN' END;

-- A row inserted without one (a raw SQL fixture, an older client) takes its category's portfolio, so the column can
-- be NOT NULL without every writer having to know about it. The application always names one.
CREATE OR REPLACE FUNCTION product.default_portfolio_code() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.portfolio_code IS NULL THEN
        NEW.portfolio_code := CASE NEW.category
            WHEN 'TERM_LIFE' THEN 'TERM' WHEN 'ENDOWMENT' THEN 'END' WHEN 'WHOLE_LIFE' THEN 'WL'
            WHEN 'ANNUITY' THEN 'IANN' WHEN 'UNIT_LINKED' THEN 'ULIP' WHEN 'GROUP_LIFE' THEN 'GRPL'
            WHEN 'EDUCATION_SAVINGS' THEN 'END' WHEN 'CREDIT_LIFE' THEN 'CRL' WHEN 'FUNERAL' THEN 'FUN' END;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_default_portfolio_code BEFORE INSERT ON product.product_definition
    FOR EACH ROW EXECUTE FUNCTION product.default_portfolio_code();

ALTER TABLE product.product_definition ALTER COLUMN portfolio_code SET NOT NULL;

ALTER TABLE product.product_version
    ADD COLUMN expected_profitability_bucket VARCHAR(20) NOT NULL DEFAULT 'REMAINING'
        CHECK (expected_profitability_bucket IN ('ONEROUS','NO_SIGNIFICANT_RISK','REMAINING')),
    ADD COLUMN measurement_model_override VARCHAR(10)
        CHECK (measurement_model_override IN ('GMM','VFA','PAA','IFRS9')),
    ALTER COLUMN ifrs_measurement_model DROP NOT NULL;

-- A credit-life product on this platform is a lender's scheme, which the register's baseline lets override to PAA
-- (MODEL_OVERRIDE_ALLOWED CRL = PAA). Every other existing version takes the register's model.
UPDATE product.product_version v SET measurement_model_override = 'PAA'
  FROM product.product_definition d
 WHERE d.product_id = v.product_id AND d.category = 'CREDIT_LIFE';
