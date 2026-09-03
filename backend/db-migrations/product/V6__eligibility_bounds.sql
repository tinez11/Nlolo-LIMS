-- What a product will accept: entry age, term and sum assured bounds.
--
-- product_version has carried an effective date, a grace period, a max loan-to-value and
-- a surrender-charge schedule, and nothing about eligibility. So nothing could refuse a
-- proposal before it failed further downstream, and nothing could explain why in terms
-- of the product.
--
-- These are consumed by Build 4's issueGates, not by anything here. The severities are
-- decided and recorded in the design spec rather than stored, because they are
-- properties of the KIND of bound, not of any one product:
--
--   * entry age  -> HARD. base_rate_table is keyed on (age_from..age_to, sex, smoker) and
--                   quotePremium already throws PremiumNotQuotableException when no cell
--                   matches. An out-of-range age is already unwritable; the gate only
--                   surfaces that earlier, where an underwriter can still act on it.
--   * term       -> HARD. Term is staff-entered configuration. A 25-year term on a
--                   20-year product is the wrong product, not a referral.
--   * sum assured-> SOFT. Above retention is not invalid business -- it is what the
--                   reinsurance treaties this platform already models exist to absorb.
--                   Blocking would refuse business the platform was built to cede.
--
-- All nullable: an unbounded dimension is a real product design rather than a gap, and
-- every version published before this has none.
--
-- No currency column here on purpose. The sum-assured bounds are read in the product's
-- own default_currency on product_definition; a second currency could disagree with it,
-- and a bound in a currency the product does not price in means nothing.
--
-- See docs/superpowers/specs/2026-09-03-build3-product-eligibility-bounds-design.md.

ALTER TABLE product.product_version
    ADD COLUMN min_entry_age    INTEGER,
    ADD COLUMN max_entry_age    INTEGER,
    ADD COLUMN min_term_months  INTEGER,
    ADD COLUMN max_term_months  INTEGER,
    ADD COLUMN min_sum_assured  NUMERIC(19,2),
    ADD COLUMN max_sum_assured  NUMERIC(19,2);

ALTER TABLE product.product_version
    ADD CONSTRAINT product_version_entry_age_sane
        CHECK ((min_entry_age IS NULL OR (min_entry_age >= 0 AND min_entry_age <= 120))
           AND (max_entry_age IS NULL OR (max_entry_age >= 0 AND max_entry_age <= 120))
           AND (min_entry_age IS NULL OR max_entry_age IS NULL OR max_entry_age >= min_entry_age)),

    ADD CONSTRAINT product_version_term_bounds_sane
        CHECK ((min_term_months IS NULL OR min_term_months > 0)
           AND (max_term_months IS NULL OR max_term_months > 0)
           AND (min_term_months IS NULL OR max_term_months IS NULL OR max_term_months >= min_term_months)),

    ADD CONSTRAINT product_version_sum_assured_bounds_sane
        CHECK ((min_sum_assured IS NULL OR min_sum_assured > 0)
           AND (max_sum_assured IS NULL OR max_sum_assured > 0)
           AND (min_sum_assured IS NULL OR max_sum_assured IS NULL OR max_sum_assured >= min_sum_assured));
