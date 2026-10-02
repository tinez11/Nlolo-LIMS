-- db-migrations/product/V21__bonus_terms.sql
-- Product step 4: a version may be with-profits.
--
-- SEPARATE TABLES, for V19's reason: a column the entity maps but a test database lacks breaks every
-- query on product_version. A row here exists only for a participating version; its absence IS
-- non-participating, which every version published before this step is.
--
-- V21, not V20: V20 is the fixed-term deposit's rate grid, which merged first.
CREATE TABLE product.version_bonus_terms (
    product_version_id   UUID PRIMARY KEY REFERENCES product.product_version(product_version_id),
    tenant_id            UUID NOT NULL,
    bonus_method         VARCHAR(10) NOT NULL CHECK (bonus_method IN ('SIMPLE','COMPOUND')),
    paid_up_participates BOOLEAN NOT NULL DEFAULT false,
    -- No default, on purpose (Q6): what bonuses add to a surrender is a contract term to be stated.
    surrender_basis      VARCHAR(20) NOT NULL CHECK (surrender_basis IN ('NONE','SUM_ASSURED_SCALE','OWN_SCALE'))
);

CREATE TABLE product.bonus_surrender_row (
    bonus_surrender_row_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL,
    product_version_id     UUID NOT NULL REFERENCES product.product_version(product_version_id),
    from_completed_years   INTEGER NOT NULL CHECK (from_completed_years >= 0),
    per_mille              NUMERIC(9,4) NOT NULL CHECK (per_mille BETWEEN 0 AND 1000)
);
CREATE UNIQUE INDEX ux_bonus_surrender_row_from ON product.bonus_surrender_row (product_version_id, from_completed_years);

ALTER TABLE product.version_bonus_terms ENABLE ROW LEVEL SECURITY;
CREATE POLICY version_bonus_terms_tenant_isolation ON product.version_bonus_terms
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.bonus_surrender_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY bonus_surrender_row_tenant_isolation ON product.bonus_surrender_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON product.version_bonus_terms TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON product.bonus_surrender_row TO app_role;
