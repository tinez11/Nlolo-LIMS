-- Credit life: §4 of the client's underwriting requirements table.
--
-- The category list exists twice on purpose -- as product.api.ProductCategory and as this
-- CHECK -- because a migration cannot call Java. ProductCategoryMigrationTest asserts the
-- Java half has not grown without this file growing with it.
--
-- Constraint name verified against the live database rather than assumed: V1 declares the
-- check inline and unnamed, so Postgres derived product_definition_category_check. A wrong
-- name here would make DROP ... IF EXISTS silently do nothing and leave the old constraint
-- in place, rejecting every CREDIT_LIFE product with no clue why.

ALTER TABLE product.product_definition
    DROP CONSTRAINT IF EXISTS product_definition_category_check;

ALTER TABLE product.product_definition
    ADD CONSTRAINT product_definition_category_check
    CHECK (category IN ('TERM_LIFE','ENDOWMENT','WHOLE_LIFE','ANNUITY','UNIT_LINKED',
                        'GROUP_LIFE','EDUCATION_SAVINGS','CREDIT_LIFE'));
