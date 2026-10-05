-- db-migrations/product/V25__unit_linked_terms.sql
-- Product step 6 (U1): what a UNIT_LINKED version offers. Funds are named by their register code (plan R2);
-- every rule the unit engine applies -- allocation, fee, mortality, death and lapse rules, the surrender and
-- premium-paying minimums, the premium floors and the sum-assured multiples -- is data on the version, never
-- code. Absent on every other version, and read only once the category says UNIT_LINKED.
CREATE TABLE product.unit_linked_terms (
    product_version_id       UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id                UUID NOT NULL,
    monthly_policy_fee       NUMERIC(19,2) NOT NULL CHECK (monthly_policy_fee >= 0),
    mortality_basis          VARCHAR(8)  NOT NULL CHECK (mortality_basis IN ('UNISEX','BY_SEX')),
    death_rule               VARCHAR(25) NOT NULL CHECK (death_rule IN ('HIGHER_OF','SUM_ASSURED_PLUS_FUND')),
    -- EXHAUSTION is the default for a true unit-linked product (spec Q6).
    lapse_rule               VARCHAR(12) NOT NULL DEFAULT 'EXHAUSTION' CHECK (lapse_rule IN ('EXHAUSTION','NON_PAYMENT')),
    -- Null = none. Inside it, non-payment lapses the policy whatever the lapse rule.
    minimum_premium_years    INTEGER CHECK (minimum_premium_years IS NULL OR minimum_premium_years BETWEEN 1 AND 50),
    minimum_surrender_years  INTEGER NOT NULL CHECK (minimum_surrender_years BETWEEN 0 AND 50),
    low_fund_warning_months  INTEGER NOT NULL CHECK (low_fund_warning_months BETWEEN 1 AND 60),
    sum_assured_multiple_min NUMERIC(9,2) NOT NULL CHECK (sum_assured_multiple_min > 0),
    sum_assured_multiple_max NUMERIC(9,2) NOT NULL,
    CHECK (sum_assured_multiple_max >= sum_assured_multiple_min)
);

CREATE TABLE product.unit_linked_fund (
    unit_linked_fund_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id  UUID NOT NULL REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id           UUID NOT NULL,
    fund_code           VARCHAR(20) NOT NULL,
    UNIQUE (product_version_id, fund_code)
);

CREATE TABLE product.unit_linked_allocation_band (
    unit_linked_allocation_band_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id             UUID NOT NULL REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id                      UUID NOT NULL,
    from_year                      INTEGER NOT NULL CHECK (from_year >= 1),
    to_year                        INTEGER CHECK (to_year IS NULL OR to_year >= from_year),
    allocation_percent             NUMERIC(7,4) NOT NULL CHECK (allocation_percent > 0 AND allocation_percent <= 100),
    UNIQUE (product_version_id, from_year)
);

CREATE TABLE product.unit_linked_mortality (
    unit_linked_mortality_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id       UUID NOT NULL REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id                UUID NOT NULL,
    age_from                 INTEGER NOT NULL CHECK (age_from BETWEEN 0 AND 120),
    -- Null on the last band: a whole-of-life policy has no maximum attained age.
    age_to                   INTEGER CHECK (age_to IS NULL OR age_to >= age_from),
    -- Null on a UNISEX table.
    sex                      VARCHAR(6) CHECK (sex IS NULL OR sex IN ('FEMALE','MALE')),
    annual_rate_per_mille    NUMERIC(9,4) NOT NULL CHECK (annual_rate_per_mille >= 0)
);
CREATE UNIQUE INDEX ux_unit_linked_mortality_band ON product.unit_linked_mortality
    (product_version_id, age_from, COALESCE(sex, 'UNISEX'));

CREATE TABLE product.unit_linked_premium_minimum (
    unit_linked_premium_minimum_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id             UUID NOT NULL REFERENCES product.unit_linked_terms(product_version_id),
    tenant_id                      UUID NOT NULL,
    frequency                      VARCHAR(12) NOT NULL
        CHECK (frequency IN ('MONTHLY','QUARTERLY','ANNUALLY','SINGLE')),
    minimum_amount                 NUMERIC(19,2) NOT NULL CHECK (minimum_amount > 0),
    UNIQUE (product_version_id, frequency)
);

ALTER TABLE product.unit_linked_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_terms_tenant_isolation ON product.unit_linked_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.unit_linked_fund ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_fund_tenant_isolation ON product.unit_linked_fund
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.unit_linked_allocation_band ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_allocation_band_tenant_isolation ON product.unit_linked_allocation_band
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.unit_linked_mortality ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_mortality_tenant_isolation ON product.unit_linked_mortality
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.unit_linked_premium_minimum ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_premium_minimum_tenant_isolation ON product.unit_linked_premium_minimum
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON product.unit_linked_terms, product.unit_linked_fund, product.unit_linked_allocation_band,
    product.unit_linked_mortality, product.unit_linked_premium_minimum TO app_role;

-- Superseded by unitlinked's fund register (spec §1, plan C1). Never written in the dev catalogue: no
-- UNIT_LINKED product or policy exists there (checked 2026-10-05). ProductApiImpl now refuses a non-empty
-- fundDefinitions list instead of writing it.
DROP TABLE product.fund_definition;
