-- db-migrations/product/V18__payout_schedule.sql
-- Product step 2: what a version pays while the life assured is ALIVE (guide §6, §7, §14, §16).
-- Two new tables and no column on product_version, so no existing product test is disturbed
-- (ddl-auto is none; a new table only matters to code that queries it) -- V17's own reasoning.

-- One row per authored payout. MATURITY and RETURN_OF_PREMIUM pay once, on the policy's own
-- maturity date, so they carry no years and no frequency: the product does not fix the term, each
-- policy does, and a row year would disagree with half the policies sold on it. SURVIVAL and
-- INCOME pay across a range of policy years at a frequency, and amount_value is the amount PER
-- POLICY YEAR, split across that year's instalments -- "3% of the sum assured a year, paid
-- monthly" is how the guide and an actuary both state it.
CREATE TABLE product.payout_schedule_row (
    payout_row_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL,
    product_version_id UUID NOT NULL REFERENCES product.product_version(product_version_id),
    row_order          INTEGER NOT NULL CHECK (row_order >= 0),
    kind               VARCHAR(20) NOT NULL
        CHECK (kind IN ('SURVIVAL','MATURITY','INCOME','RETURN_OF_PREMIUM')),
    from_policy_year   INTEGER,
    to_policy_year     INTEGER,
    amount_basis       VARCHAR(20) NOT NULL
        CHECK (amount_basis IN ('PERCENT_OF_SA','FIXED','PERCENT_OF_PREMIUMS')),
    amount_value       NUMERIC(19,4) NOT NULL CHECK (amount_value > 0),
    frequency          VARCHAR(12) CHECK (frequency IN ('ANNUAL','SEMI_ANNUAL','QUARTERLY','MONTHLY')),
    CONSTRAINT payout_row_shape CHECK (
        (kind IN ('MATURITY','RETURN_OF_PREMIUM')
            AND from_policy_year IS NULL AND to_policy_year IS NULL AND frequency IS NULL)
        OR (kind IN ('SURVIVAL','INCOME')
            AND from_policy_year >= 1 AND to_policy_year >= from_policy_year AND frequency IS NOT NULL)),
    -- Only a premium return is valued off premiums, and a premium return is valued off nothing
    -- else. Both directions, because either mistake prices a benefit from the wrong base entirely.
    CONSTRAINT payout_row_basis CHECK ((kind = 'RETURN_OF_PREMIUM') = (amount_basis = 'PERCENT_OF_PREMIUMS'))
);
CREATE UNIQUE INDEX ux_payout_row_order ON product.payout_schedule_row (product_version_id, row_order);

-- Per-version servicing terms. free_look_days is nullable HERE because group and credit life have
-- none -- their cancellation is the scheme contract's -- and PayoutPlanValidator is what requires
-- it on an authored individual version. A CHECK cannot ask what category the product is.
CREATE TABLE product.version_payout_terms (
    product_version_id                    UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id                             UUID NOT NULL,
    free_look_days                        INTEGER CHECK (free_look_days BETWEEN 1 AND 365),
    proof_of_life_interval_months         INTEGER CHECK (proof_of_life_interval_months BETWEEN 1 AND 60),
    survival_benefits_deducted_from_death BOOLEAN,
    death_benefit_premium_percent         NUMERIC(7,4)
        CHECK (death_benefit_premium_percent > 0 AND death_benefit_premium_percent <= 1000)
);

ALTER TABLE product.payout_schedule_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY payout_schedule_row_tenant_isolation ON product.payout_schedule_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.version_payout_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_payout_terms_tenant_isolation ON product.version_payout_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON product.payout_schedule_row TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON product.version_payout_terms TO app_role;
