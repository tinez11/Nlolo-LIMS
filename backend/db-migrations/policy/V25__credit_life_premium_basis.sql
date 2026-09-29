-- HOW a lender's rate turns into money, which is not the same question as what the rate is.
--
-- `premium_rate_percent` let two lenders on one filed product pay different RATES. It did not
-- let them pay on different BASES, and the two real client schedules do exactly that:
--
--   Bumaco (May)  charges a flat percent of the disbursed amount. A 2-month loan and a
--                 12-month loan of the same size pay the same premium. Verified against six
--                 loans of terms 2, 4, 6, 6, 12 and 12: every one is 0.6% of principal, and
--                 the sheet's own total of 111,000 on 18,500,000 agrees.
--
--   LOLC (June)   charges the rate once per POLICY YEAR, each year on the principal still
--                 outstanding at the start of it, straight-line. An 18-month loan pays
--                 0.5% of the full amount and then 0.5% of a third of it. Verified against
--                 terms of 12, 18, 23 and 24 months, including 10,500,000 over 23 months
--                 whose second year is 25,108.6956... to the last decimal the sheet carries.
--
-- Neither is what the platform computed, which was the rate per annum multiplied by the term
-- in years -- the assumption recorded in the design spec's table ("An agreed percent per year
-- of the original loan amount, charged once") when the question could not be answered. It is
-- kept as PER_ANNUM_ON_PRINCIPAL, and it is the default for every existing row, because a
-- widening migration must not silently reprice a scheme somebody has already sold.
--
-- Charged ONCE at enrolment in all three cases (client, 2026-09-29). LOLC's per-year figures
-- are a way of arriving at one number, not an instruction to invoice annually, so the
-- one-file-one-invoice correspondence holds for every basis.
ALTER TABLE policy.group_scheme
    ADD COLUMN premium_basis VARCHAR(30);

UPDATE policy.group_scheme
    SET premium_basis = 'PER_ANNUM_ON_PRINCIPAL'
    WHERE benefit_basis = 'AMORTISING_LOAN';

ALTER TABLE policy.group_scheme
    ADD CONSTRAINT group_scheme_premium_basis_check
        CHECK (premium_basis IS NULL OR premium_basis IN
            ('FLAT_ON_PRINCIPAL', 'PER_ANNUM_ON_PRINCIPAL', 'ANNUAL_ON_DECLINING_BALANCE'));

-- Present exactly when a rate is, and for the same reason: a basis without a rate prices
-- nothing, and a rate without a basis does not say what to do with itself. Mirrors
-- chk_group_scheme_rate_iff_loan_basis rather than restating the basis test, so the two
-- cannot drift apart.
ALTER TABLE policy.group_scheme
    ADD CONSTRAINT chk_group_scheme_premium_basis_iff_rate
        CHECK ((premium_rate_percent IS NOT NULL) = (premium_basis IS NOT NULL));
