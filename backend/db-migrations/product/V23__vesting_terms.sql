-- db-migrations/product/V23__vesting_terms.sql
-- Product step 5 (D2): a deferred annuity's vesting terms -- the window, what it vests into when
-- nobody says otherwise, the lump-sum cap, and whether it is locked before it vests. Its own table,
-- read only after the product's category says ANNUITY (D1 R2), so no other test class needs it.
CREATE TABLE product.version_vesting_terms (
    product_version_id        UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id                 UUID NOT NULL,
    min_vesting_age           INTEGER NOT NULL CHECK (min_vesting_age BETWEEN 0 AND 120),
    max_vesting_age           INTEGER NOT NULL CHECK (max_vesting_age BETWEEN 0 AND 120),
    default_form_code         VARCHAR(30) NOT NULL,
    default_frequency         VARCHAR(12) NOT NULL
        CHECK (default_frequency IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
    max_commutation_percent   NUMERIC(9,4) NOT NULL CHECK (max_commutation_percent BETWEEN 0 AND 100),
    -- No default, on purpose (spec Q9): a locked pension and an unlocked deferred annuity are both
    -- real products, and the version must say which it is.
    surrender_before_vesting  BOOLEAN NOT NULL,
    CHECK (min_vesting_age <= max_vesting_age)
);
ALTER TABLE product.version_vesting_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_vesting_terms_tenant_isolation ON product.version_vesting_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON product.version_vesting_terms TO app_role;
