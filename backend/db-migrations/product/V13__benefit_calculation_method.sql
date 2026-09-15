-- benefit_schedule.calculation_method becomes a real vocabulary, and a benefit can say what it pays.
--
-- It was an unconstrained VARCHAR(50). Across 146 versions it held three rows: two carrying the
-- typed prose 'untill death' and one carrying 'SUM_ASSURED'. Nothing noticed, because NO
-- COMPUTATION HAS EVER READ THIS COLUMN -- it is displayed on one product screen and used nowhere
-- else. Issuance wrote a hardcoded DEATH coverage whatever a product declared.
--
-- NORMALISE, don't grandfather -- and that is a deliberate departure from V5, V8 and V9. Those
-- used NOT VALID because the columns they constrained drove real money (a rating multiplier, a
-- rate per mille), so rewriting history would have altered priced contracts. This column has
-- never decided anything, so mapping a typo to the value it plainly meant changes no contract and
-- no premium -- and a fully VALIDATED check becomes achievable where V5 had to settle for less.
--
-- Anything outside the three values maps to SUM_ASSURED. With three rows in this database that is
-- a rounding error, and the alternative -- failing the migration on unknown data -- would block a
-- deployment over a label that has never decided anything.

UPDATE product.benefit_schedule
   SET calculation_method = 'SUM_ASSURED'
 WHERE calculation_method NOT IN ('SUM_ASSURED','PERCENTAGE_OF_SUM_ASSURED','FLAT_AMOUNT');

ALTER TABLE product.benefit_schedule
    ADD COLUMN benefit_percent NUMERIC(5,2),
    ADD COLUMN flat_amount     NUMERIC(19,2);

ALTER TABLE product.benefit_schedule
    ADD CONSTRAINT benefit_schedule_calculation_method_known
        CHECK (calculation_method IN ('SUM_ASSURED','PERCENTAGE_OF_SUM_ASSURED','FLAT_AMOUNT'));

-- Exactly the amount its method needs, and no other. Same shape-constraint arrangement as
-- rating_table_age_bounds_shape.
ALTER TABLE product.benefit_schedule
    ADD CONSTRAINT benefit_schedule_amount_shape CHECK (
        (calculation_method = 'SUM_ASSURED'
            AND benefit_percent IS NULL AND flat_amount IS NULL)
     OR (calculation_method = 'PERCENTAGE_OF_SUM_ASSURED'
            AND benefit_percent IS NOT NULL AND benefit_percent > 0 AND benefit_percent <= 100
            AND flat_amount IS NULL)
     OR (calculation_method = 'FLAT_AMOUNT'
            AND flat_amount IS NOT NULL AND flat_amount > 0
            AND benefit_percent IS NULL));

COMMENT ON COLUMN product.benefit_schedule.benefit_percent IS
    'Set for PERCENTAGE_OF_SUM_ASSURED and null otherwise. A critical-illness rider at 25 pays a '
    'quarter of the policy sum assured; before this column every claim type was valued at the '
    'whole of it.';
