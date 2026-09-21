-- Credit life: a scheme whose members are LOANS, and whose cover falls as they repay.
--
-- Requires V13 (freeform members): a borrower is a name on a lender's schedule, not a
-- registered party. See docs/superpowers/specs/2026-09-21-credit-life-design.md.
--
-- Both constraint names below were verified against a live database before being dropped.
-- V9 declares the basis check inline and unnamed, so Postgres derived
-- group_scheme_benefit_basis_check; DROP CONSTRAINT with a wrong name is a no-op that
-- would silently leave the old three-value check in force.

-- A fourth basis. AMORTISING_LOAN carries no scheme-level parameter, exactly as GRADED
-- does not: every borrower's loan is different, so the amount lives on the member.
ALTER TABLE policy.group_scheme DROP CONSTRAINT group_scheme_basis_parameter_present;
ALTER TABLE policy.group_scheme DROP CONSTRAINT group_scheme_benefit_basis_check;

ALTER TABLE policy.group_scheme
    ADD CONSTRAINT group_scheme_benefit_basis_check
    CHECK (benefit_basis IN ('FLAT','SALARY_MULTIPLE','GRADED','AMORTISING_LOAN'));

ALTER TABLE policy.group_scheme
    ADD CONSTRAINT group_scheme_basis_parameter_present CHECK (
        (benefit_basis = 'FLAT'            AND flat_benefit_amount IS NOT NULL AND salary_multiple IS NULL)
     OR (benefit_basis = 'SALARY_MULTIPLE' AND salary_multiple     IS NOT NULL AND flat_benefit_amount IS NULL)
     OR (benefit_basis = 'GRADED'          AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL)
     OR (benefit_basis = 'AMORTISING_LOAN' AND flat_benefit_amount IS NULL     AND salary_multiple IS NULL)
    );

-- How this lender's loans repay principal, stated once per scheme from the lending
-- agreement. It was to have been INFERRED from each borrower's own instalment, but
-- neither real client schedule carries an instalment or even an interest rate, so there
-- is nothing to infer from.
--
-- Nullable only because the product does not yet know whether cover declines at all --
-- an open client question. Make it NOT NULL in the migration that settles it.
ALTER TABLE policy.group_scheme
    ADD COLUMN interest_method VARCHAR(20)
        CHECK (interest_method IS NULL OR interest_method IN ('REDUCING_BALANCE','FLAT_RATE'));

-- A member of a credit-life scheme IS a loan. Two loans to the same borrower are two
-- members, which is right: each covers its own debt.
ALTER TABLE policy.policy_member
    ADD COLUMN loan_account_number       VARCHAR(50),
    ADD COLUMN loan_principal_amount     NUMERIC(19,2)
        CHECK (loan_principal_amount IS NULL OR loan_principal_amount > 0),
    ADD COLUMN loan_annual_rate_percent  NUMERIC(6,3)
        CHECK (loan_annual_rate_percent IS NULL OR loan_annual_rate_percent >= 0),
    ADD COLUMN loan_term_months          INTEGER
        CHECK (loan_term_months IS NULL OR loan_term_months > 0),
    ADD COLUMN loan_repayment_frequency  VARCHAR(20)
        CHECK (loan_repayment_frequency IS NULL
               OR loan_repayment_frequency IN ('MONTHLY','QUARTERLY')),
    ADD COLUMN loan_disbursement_date    DATE,
    ADD COLUMN loan_first_repayment_date DATE;

-- The rate is nullable-but-required-together with the rest, and may be ZERO: both real
-- lenders' files state no rate, and straight-line decline never reads one.

-- All or nothing. A member with a principal but no term would produce a schedule the
-- application has to guess at, and guessing puts a fabricated death benefit on a real
-- contract -- the same reasoning as group_scheme_basis_parameter_present.
ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_loan_complete CHECK (
        (loan_account_number IS NULL AND loan_principal_amount IS NULL
            AND loan_annual_rate_percent IS NULL AND loan_term_months IS NULL
            AND loan_repayment_frequency IS NULL
            AND loan_disbursement_date IS NULL AND loan_first_repayment_date IS NULL)
     OR (loan_account_number IS NOT NULL AND loan_principal_amount IS NOT NULL
            AND loan_annual_rate_percent IS NOT NULL AND loan_term_months IS NOT NULL
            AND loan_repayment_frequency IS NOT NULL
            AND loan_disbursement_date IS NOT NULL AND loan_first_repayment_date IS NOT NULL)
    );

ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_repaid_after_disbursed CHECK (
        loan_first_repayment_date IS NULL
        OR loan_first_repayment_date >= loan_disbursement_date
    );

-- The member key. This is what makes a resubmitted enrolment file idempotent, and it is
-- the identity a freeform borrower otherwise lacks -- V13 deliberately allows two
-- freeform members to share a name, because a name is not an identity. A loan account
-- number is.
--
-- Scoped to ACTIVE so a loan that is settled and later refinanced under the same account
-- number can be enrolled again: a restructure is exit-and-re-enrol, not an amendment.
CREATE UNIQUE INDEX ux_policy_member_active_loan
    ON policy.policy_member (policy_number, loan_account_number)
    WHERE status = 'ACTIVE' AND loan_account_number IS NOT NULL;
