-- db-migrations/product/V9__rating_table_sum_assured_bounds.sql
-- Give SUM_ASSURED_BAND rating factors real bounds, so a sum assured can actually resolve to one.
--
-- THIS IS V5'S DEFECT, ONE FACTOR TYPE OVER, and it was found the same way: by a real product
-- priced through the console producing a premium nobody could explain.
--
-- V5's own words about AGE: "band was free text ... and AGE was rated by EXACT STRING MATCH
-- against whatever a publisher typed. UnderwritingApiImpl could not produce a string to match
-- ... so it returned the sentinel UNKNOWN, which matches nothing, and every applicant got the
-- neutral 1.0 multiplier."
--
-- SUM_ASSURED_BAND works exactly that way today, and worse, because the mismatch is invisible on
-- both sides. UnderwritingApiImpl.resolveSumAssuredBand returns one of three HARDCODED strings --
-- 'LOW', 'MEDIUM', 'HIGH', on thresholds of 2,000,000 and 10,000,000 written in Java -- while the
-- product author types a band into a free-text box. A real published product carries the band
-- '5000000', which matches none of the three, resolves to the neutral 1.0, and prices every
-- policy as though the factor did not exist. Nothing warned anybody: the row is there, the
-- multiplier is there, the console shows it, and it does nothing.
--
-- The thresholds being in code is the deeper half. A product's own bands are the product's
-- business, and three fixed tiers cannot express a real rating table; an author had no way to
-- learn that the only strings that work are three words nobody put on screen.
--
-- Bounds, not a parser, for the same reason V5 chose bounds for AGE: the bands already in this
-- database are free text of every shape ('5000000', 'LOW', '0-5000000'), and a bare '5000000' is
-- either a band starting at five million or a band ending there. Nothing in the record says
-- which, and guessing would put that guess between an actuary's table and a customer's premium.
--
-- sum_assured_to is INCLUSIVE, matching age_to and base_rate_table.
--
-- NOT VALID, and NULL bounds are meaningful: a SUM_ASSURED_BAND row published before this
-- migration has no bounds and resolves for nobody -- which is exactly what it did yesterday, so
-- grandfathering changes nothing observable. New and republished versions must supply bounds.
-- `band` is kept as the human-readable label an actuary checks against their own paperwork, the
-- same role it plays for AGE; it simply stops being what the platform matches on.

ALTER TABLE product.rating_table
    ADD COLUMN sum_assured_from NUMERIC(18,2),
    ADD COLUMN sum_assured_to   NUMERIC(18,2);

ALTER TABLE product.rating_table
    ADD CONSTRAINT rating_table_sum_assured_bounds_shape CHECK (
        (factor_type <> 'SUM_ASSURED_BAND' AND sum_assured_from IS NULL AND sum_assured_to IS NULL)
        OR (factor_type = 'SUM_ASSURED_BAND'
            AND ((sum_assured_from IS NULL AND sum_assured_to IS NULL)
                 OR (sum_assured_from IS NOT NULL AND sum_assured_to IS NOT NULL
                     AND sum_assured_from >= 0 AND sum_assured_to >= sum_assured_from)))
    ) NOT VALID;

COMMENT ON COLUMN product.rating_table.sum_assured_from IS
    'Inclusive lower bound of a SUM_ASSURED_BAND row. NULL on a row published before V9, which '
    'resolves for no sum assured at all -- the same as it did before, since the band text it was '
    'matched on could never be produced by the resolver either.';

-- "Find the band covering this sum assured" is the read this exists for.
CREATE INDEX idx_rating_table_sum_assured_range
    ON product.rating_table (product_version_id, sum_assured_from, sum_assured_to)
    WHERE factor_type = 'SUM_ASSURED_BAND';
