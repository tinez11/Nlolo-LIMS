-- The INSURER issues the member reference, because the lender has none to give.
--
-- Client answer, 2026-09-22: one policy number goes to the bank and sheets come back,
-- and nothing on those sheets distinguishes one borrower from another. V14 keyed a
-- credit-life member on a loan_account_number the lender was to supply; they cannot.
--
-- So we mint one at enrolment and print it on the report that goes back with the file.
-- From then on the lender can quote it for an exit or a correction. See
-- docs/superpowers/specs/2026-09-21-credit-life-design.md section 2.1a.

-- A SEQUENCE, not a per-scheme counter column.
--
-- A counter on group_scheme would be a read-modify-write, and two members added
-- concurrently would both read the same value and one reference would silently be lost --
-- the exact lost-update shape that was found as a Critical on regreporting's fact tables.
-- nextval is atomic and cannot be raced. The cost is that numbers are not contiguous
-- within a scheme, which is cosmetic: nobody counts them, they quote them.
CREATE SEQUENCE policy.member_reference_seq;

GRANT USAGE, SELECT ON SEQUENCE policy.member_reference_seq TO app_role;

ALTER TABLE policy.policy_member
    ADD COLUMN member_reference VARCHAR(30);

-- Unique across the platform, not merely within a scheme: a lender quoting a reference
-- back at us should never be ambiguous, and the sequence makes it free.
CREATE UNIQUE INDEX ux_policy_member_reference
    ON policy.policy_member (member_reference)
    WHERE member_reference IS NOT NULL;

-- ux_policy_member_active_loan keyed on the loan account number, which no lender supplies.
-- An index over a column that is always NULL guards nothing -- in Postgres a NULL never
-- collides -- so it is replaced rather than left to look like protection.
DROP INDEX IF EXISTS policy.ux_policy_member_active_loan;

-- The loan account number is now OPTIONAL. It stays on the table because a lender may
-- one day send one and it is worth keeping when they do; it is no longer the key, so it
-- no longer belongs in the all-or-nothing loan check.
ALTER TABLE policy.policy_member DROP CONSTRAINT chk_policy_member_loan_complete;

ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_loan_complete CHECK (
        (loan_principal_amount IS NULL AND loan_annual_rate_percent IS NULL
            AND loan_term_months IS NULL AND loan_repayment_frequency IS NULL
            AND loan_disbursement_date IS NULL AND loan_first_repayment_date IS NULL)
     OR (loan_principal_amount IS NOT NULL AND loan_annual_rate_percent IS NOT NULL
            AND loan_term_months IS NOT NULL AND loan_repayment_frequency IS NOT NULL
            AND loan_disbursement_date IS NOT NULL AND loan_first_repayment_date IS NOT NULL)
    );

-- A member carrying a loan is a credit-life member, and every one of those must be
-- quotable. Without this a borrower could be enrolled with no way for the lender to name
-- them afterwards -- which is the whole problem this migration exists to solve.
ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_loan_has_reference CHECK (
        loan_principal_amount IS NULL OR member_reference IS NOT NULL
    );
