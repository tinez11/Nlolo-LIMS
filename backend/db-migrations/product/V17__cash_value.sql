-- Cash value: what a savings policy is worth as it runs (product step 1, task 2). The guide's
-- model (§5, §6) -- surrender value per policy year, from a table the actuary supplies, not a
-- running balance of premiums. Two new tables, no column on product_version, so no existing
-- product test is disturbed (ddl-auto is none; a new table only matters to code that queries it).

-- The per-year scale. cash_value_per_mille is per 1,000 of sum assured at that policy year;
-- paid_up_per_mille is the reduced sum assured per 1,000 if the customer stops paying (TABLE basis;
-- null where the product uses the PROPORTIONATE basis, which needs no table). Age bands optional,
-- the base-rate convention: null = one scale for every entry age.
CREATE TABLE product.cash_value_table (
    cash_value_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_version_id  UUID NOT NULL REFERENCES product.product_version(product_version_id),
    policy_year         INTEGER NOT NULL CHECK (policy_year >= 1),
    age_from            INTEGER,
    age_to              INTEGER,
    cash_value_per_mille NUMERIC(12,4) NOT NULL CHECK (cash_value_per_mille >= 0),
    paid_up_per_mille    NUMERIC(12,4) CHECK (paid_up_per_mille IS NULL OR paid_up_per_mille >= 0),
    CONSTRAINT cash_value_age_range_shape CHECK (
        (age_from IS NULL AND age_to IS NULL)
        OR (age_from IS NOT NULL AND age_to IS NOT NULL AND age_from >= 0 AND age_to >= age_from))
);
-- One scale per (version, policy year, entry-age band). COALESCE so the unbanded rows collide on
-- (version, policy_year) exactly as the base-rate table's unbanded rows do.
CREATE UNIQUE INDEX ux_cash_value_cell
    ON product.cash_value_table (product_version_id, policy_year, COALESCE(age_from, 0));
CREATE INDEX idx_cash_value_version ON product.cash_value_table (product_version_id, policy_year);

-- Per-version configuration. Its presence is what marks a version as a cash-value (savings) product.
-- basis_reference/basis_date are the actuarial sign-off: a value table cannot be published without
-- one, the same discipline the TIRA filing enforces. min_years_for_value is 2 or 3 -- the years
-- before any surrender or paid-up value exists -- required, no default, because the figure is the
-- product's. paid_up_basis picks how a paid-up sum assured is found.
CREATE TABLE product.cash_value_config (
    product_version_id  UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id           UUID NOT NULL,
    basis_reference     VARCHAR(200) NOT NULL,
    basis_date          DATE NOT NULL,
    paid_up_basis       VARCHAR(20) NOT NULL CHECK (paid_up_basis IN ('PROPORTIONATE','TABLE')),
    min_years_for_value INTEGER NOT NULL CHECK (min_years_for_value BETWEEN 2 AND 3),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE product.cash_value_table ENABLE ROW LEVEL SECURITY;
CREATE POLICY cash_value_table_tenant_isolation ON product.cash_value_table
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.cash_value_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY cash_value_config_tenant_isolation ON product.cash_value_config
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON product.cash_value_table TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON product.cash_value_config TO app_role;
