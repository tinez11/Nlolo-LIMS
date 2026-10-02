-- db-migrations/product/V20__deposit_rate_grid.sql
-- A fixed-term deposit's rates: deposit band x term -> rate FOR THE TERM (the user's savings plan,
-- spec 2026-10-02). Its presence makes the version a deposit; the version is also ACCOUNT-basis
-- (V19), with an account plan the server builds and that charges nothing.
--
-- A SEPARATE TABLE for V19's reason: ddl-auto is none, and a column on product_version that a test
-- database lacks would break every class that reads a version.
--
-- A band is named by its START only. It runs to the next band's start, so the grid can hold no gap
-- and no overlap -- the spec's D9, made a property of the shape instead of a rule to check.
CREATE TABLE product.deposit_rate_row (
    deposit_rate_row_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    product_version_id  UUID NOT NULL REFERENCES product.product_version(product_version_id),
    min_amount          NUMERIC(19,2) NOT NULL CHECK (min_amount > 0),
    term_months         INTEGER NOT NULL CHECK (term_months BETWEEN 1 AND 120),
    rate_percent        NUMERIC(7,4) NOT NULL CHECK (rate_percent BETWEEN 0 AND 100)
);
CREATE UNIQUE INDEX ux_deposit_rate_row_cell ON product.deposit_rate_row (product_version_id, min_amount, term_months);

ALTER TABLE product.deposit_rate_row ENABLE ROW LEVEL SECURITY;
CREATE POLICY deposit_rate_row_tenant_isolation ON product.deposit_rate_row
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON product.deposit_rate_row TO app_role;
