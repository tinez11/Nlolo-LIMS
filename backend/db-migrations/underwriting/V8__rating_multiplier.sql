-- db-migrations/underwriting/V8__rating_multiplier.sql
-- Carries the rating table's verdict out of the engine, so it can reach a price.
--
-- product.rating_table has held real per-band multipliers since V1, and V5 gave AGE rows real
-- age_from/age_to bounds so an applicant's age finally resolved to one. Underwriting resolved
-- both factors on every assessment -- and then threw the number away. SimpleRulesEngine used the
-- combined multiplier to pick an OUTCOME and to derive a loading percent, and nothing else ever
-- saw it: policy.UnderwritingDecisionEventListener priced every accepted case as
-- sumAssured x (flat base rate) x (1 + loading), with no multiplier term anywhere. On an ACCEPT a
-- 25-year-old and a 55-year-old with the same cover paid exactly the same premium. The rating
-- table did nothing but nudge a decision.
--
-- Storing it rather than recomputing it downstream is deliberate. It is a fact about the applicant
-- as they were rated -- resolved from THIS product version's table, at the age they were when the
-- evidence was assessed. Recomputing at issuance would silently re-rate a case on a birthday or on
-- a republished version, and would give an underwriter's quoted premium a different basis from the
-- one the customer is billed.
ALTER TABLE underwriting.underwriting_case
    ADD COLUMN rating_multiplier NUMERIC(9,4);

COMMENT ON COLUMN underwriting.underwriting_case.rating_multiplier IS
    'Combined product.rating_table multiplier the engine rated this case at (age band x sum assured band). '
    'Recomputed with the recommendation on every assessment, and applied to the premium at issuance. '
    'NULL for a case last assessed before this column existed.';

-- Deliberately no backfill and no DEFAULT 1.0. NULL means "this case was rated before the rating
-- table reached the premium", which is true and is not the same claim as "this case was rated at
-- standard". Issuance reads NULL as neutral because that is what those policies were in fact
-- priced at, but the column must not assert an actuarial fact nobody established.
--
-- Not on the decision_* columns and not gated on outcome: the multiplier is what the product's
-- table says about this applicant, independent of whether a person accepted, loaded or declined
-- the case, and it stays true if an underwriter overrides the recommendation entirely.
