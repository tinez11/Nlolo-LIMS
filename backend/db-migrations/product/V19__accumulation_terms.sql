-- db-migrations/product/V19__accumulation_terms.sql
-- Product step 3: a version may be valued by an ACCOUNT rather than step 1's SCALE.
--
-- A SEPARATE TABLE, NOT A COLUMN ON product_version, and that is deliberate. ddl-auto is none, so
-- a column the entity maps but a test database lacks breaks EVERY query on product_version -- and
-- some fifty test classes read it. A row here exists only for an ACCOUNT version; its absence IS
-- the SCALE basis, which is what every version published before this step is.
CREATE TABLE product.version_accumulation_terms (
    product_version_id      UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id               UUID NOT NULL,
    value_basis             VARCHAR(10) NOT NULL CHECK (value_basis = 'ACCOUNT'),
    guaranteed_rate_percent NUMERIC(7,4) NOT NULL CHECK (guaranteed_rate_percent BETWEEN 0 AND 100),
    minimum_balance         NUMERIC(19,2) NOT NULL CHECK (minimum_balance >= 0)
);

-- One row per run of policy years. to_policy_year NULL = "and every year after"; the validator
-- requires exactly one such row, the last. A CHECK cannot see the other rows, so contiguity and
-- coverage are the validator's -- these are the per-row facts.
CREATE TABLE product.accumulation_charge (
    accumulation_charge_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                       UUID NOT NULL,
    product_version_id              UUID NOT NULL REFERENCES product.product_version(product_version_id),
    from_policy_year                INTEGER NOT NULL CHECK (from_policy_year >= 1),
    to_policy_year                  INTEGER CHECK (to_policy_year IS NULL OR to_policy_year >= from_policy_year),
    contribution_allocation_percent NUMERIC(7,4) NOT NULL CHECK (contribution_allocation_percent BETWEEN 0 AND 100),
    transfer_allocation_percent     NUMERIC(7,4) NOT NULL CHECK (transfer_allocation_percent BETWEEN 0 AND 100),
    monthly_policy_fee              NUMERIC(19,2) NOT NULL CHECK (monthly_policy_fee >= 0)
);
CREATE UNIQUE INDEX ux_accumulation_charge_from ON product.accumulation_charge (product_version_id, from_policy_year);

-- The account-value maturity (step 3). The allow-list widens; the ROP <-> PERCENT_OF_PREMIUMS rule
-- (payout_row_basis) is unchanged, and a new rule keeps ACCOUNT_VALUE on MATURITY rows only.
-- The name below is the auto-generated one V18's inline CHECK received, confirmed against the dev
-- database -- a DROP with a wrong name fails the migration outright.
ALTER TABLE product.payout_schedule_row DROP CONSTRAINT payout_schedule_row_amount_basis_check;
ALTER TABLE product.payout_schedule_row ADD CONSTRAINT payout_schedule_row_amount_basis_check
    CHECK (amount_basis IN ('PERCENT_OF_SA','FIXED','PERCENT_OF_PREMIUMS','ACCOUNT_VALUE'));
ALTER TABLE product.payout_schedule_row ADD CONSTRAINT payout_row_account_value_on_maturity
    CHECK (amount_basis <> 'ACCOUNT_VALUE' OR kind = 'MATURITY');

ALTER TABLE product.version_accumulation_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_accumulation_terms_tenant_isolation ON product.version_accumulation_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.accumulation_charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY accumulation_charge_tenant_isolation ON product.accumulation_charge
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON product.version_accumulation_terms TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON product.accumulation_charge TO app_role;
