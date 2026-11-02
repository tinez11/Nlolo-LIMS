-- db-migrations/policy/V44__credit_life_agreement_first.sql
-- Credit life, agreement first (2026-11-01). A loan is priced only on the lender's yearly agreement (V43), and a
-- claim pays the balance on the lender's statement, so a scheme's set-up no longer states a rate, how its loans repay,
-- or a premium of its own. Four changes follow:
--
-- 1. A credit-life scheme may carry no rate. One set up before agreements keeps the rate it was written on; the rate
--    and its basis still come together (chk_group_scheme_premium_basis_iff_rate, V25), and nothing but credit life
--    may carry one.
-- 2. A credit-life contract records a nil premium. Each accepted file is invoiced; the figure typed at set-up was
--    billed to nobody and paid no commission (billing's premiumPerEnrolment), so it is no longer asked for.
-- 3. An agreement states the youngest borrower it takes, 18 unless it says otherwise, and its shortest loan defaults
--    to a month: "they all start with one year" is how a short loan is CHARGED, not a refusal -- the lender's own file
--    carries four-month loans priced as a year.
-- 4. A lender's exits file may name a loan by the lender's own account number, the one thing the lender knows it by.
--    The row keeps the number it was given, so the report reads beside the lender's file.
--
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE policy.group_scheme DROP CONSTRAINT IF EXISTS chk_group_scheme_rate_iff_loan_basis;
ALTER TABLE policy.group_scheme
    ADD CONSTRAINT chk_group_scheme_rate_iff_loan_basis
        CHECK (premium_rate_percent IS NULL OR benefit_basis = 'AMORTISING_LOAN');

ALTER TABLE policy.policy DROP CONSTRAINT IF EXISTS chk_premium_amount_positive;
ALTER TABLE policy.policy
    ADD CONSTRAINT chk_premium_amount_positive
        CHECK (premium_amount > 0 OR (premium_amount = 0 AND product_category = 'CREDIT_LIFE')) NOT VALID;

ALTER TABLE policy.credit_life_agreement ADD COLUMN IF NOT EXISTS min_age INTEGER NOT NULL DEFAULT 18;
ALTER TABLE policy.credit_life_agreement ALTER COLUMN min_term_months SET DEFAULT 1;
ALTER TABLE policy.credit_life_agreement DROP CONSTRAINT IF EXISTS chk_cla_age;
ALTER TABLE policy.credit_life_agreement
    ADD CONSTRAINT chk_cla_age CHECK (min_age >= 18 AND max_age >= min_age AND max_age <= 100);

-- IF EXISTS: a test that loads the agreements without the exits files (V21) still migrates.
ALTER TABLE IF EXISTS policy.exit_submission_row ADD COLUMN IF NOT EXISTS loan_account_number VARCHAR(50);
