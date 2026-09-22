-- The report tells the lender the reference we issued.
--
-- V16 made the insurer the one who names a borrower, because the lender has no identifier
-- of their own. That is only useful if they learn it, and the report that goes back with
-- their file is where they learn it.
--
-- Written at ACCEPTANCE, beside policy_member_id, because that is when the member -- and
-- therefore the reference -- exists.

ALTER TABLE policy.enrolment_submission_row
    ADD COLUMN member_reference VARCHAR(30);

-- V15 required a loan_account_number on any row that would become cover. V16 made that
-- column optional, because the lender has none to give -- so the check now refuses every
-- real row. Restated without it.
--
-- The rest of the check stands and is the point of it: a row recorded as enrollable must
-- carry everything needed to create the member, or acceptance can reach a row it cannot
-- enrol half-way through a file, having already put earlier borrowers on risk.
ALTER TABLE policy.enrolment_submission_row
    DROP CONSTRAINT chk_enrolment_row_enrollable_is_complete;

ALTER TABLE policy.enrolment_submission_row
    ADD CONSTRAINT chk_enrolment_row_enrollable_is_complete CHECK (
        outcome = 'REJECTED'
        OR (borrower_full_name IS NOT NULL
            AND borrower_date_of_birth IS NOT NULL AND loan_principal_amount IS NOT NULL
            AND loan_term_months IS NOT NULL AND disbursement_date IS NOT NULL));
