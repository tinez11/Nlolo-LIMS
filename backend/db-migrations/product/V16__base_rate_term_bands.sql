-- Term-banded base rates (product step 1, D4). rate_per_mille is an ANNUAL rate, and a 5-year and
-- a 20-year term are not the same risk per 1,000 -- most of all for endowment, where the term drives
-- the price. Until now the table had no term dimension, so every term on a product priced the same.
--
-- Both columns null = the rate applies to ANY term (every row that exists today, unchanged). Both
-- set = the rate applies only to a policy term within [term_from_months, term_to_months], inclusive.
-- A version either bands a (sex, smoker) cell by term or it does not -- the application refuses an
-- unbanded row overlapping a banded one, so at most one cell ever matches a given (age, sex, smoker,
-- term). A policy with no term cannot match a banded row and is refused at pricing, by design.

ALTER TABLE product.base_rate_table
    ADD COLUMN term_from_months INTEGER,
    ADD COLUMN term_to_months   INTEGER;

ALTER TABLE product.base_rate_table
    ADD CONSTRAINT base_rate_term_range_shape CHECK (
        (term_from_months IS NULL AND term_to_months IS NULL)
        OR (term_from_months IS NOT NULL AND term_to_months IS NOT NULL
            AND term_from_months >= 1 AND term_to_months >= term_from_months)
    );

-- The unique cell gains the term. COALESCE so the unbanded rows (term_from NULL) still collide with
-- each other on (version, age_from, sex, smoker) exactly as before -- two unbanded rows for one cell
-- would price by row order, which is the defect the unique index exists to prevent.
ALTER TABLE product.base_rate_table DROP CONSTRAINT IF EXISTS base_rate_unique_cell;
DROP INDEX IF EXISTS product.ux_base_rate_cell_with_term;
CREATE UNIQUE INDEX ux_base_rate_cell_with_term
    ON product.base_rate_table (product_version_id, age_from, sex, smoker_status, COALESCE(term_from_months, 0));

DROP INDEX IF EXISTS product.idx_base_rate_version;
CREATE INDEX idx_base_rate_version
    ON product.base_rate_table (product_version_id, age_from, age_to, term_from_months, term_to_months);
