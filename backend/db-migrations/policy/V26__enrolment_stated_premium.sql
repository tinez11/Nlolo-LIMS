-- What the LENDER said the premium was, beside what the insurer computed.
--
-- Both real schedules carry a premium column and the parser ignored it, which is defensible as
-- far as it goes -- the insurer prices the cover, not the counterparty -- but it threw away the
-- one figure that makes a file reconcilable. If the two disagree, nothing noticed: the file
-- parsed, their number was discarded, and an invoice went out for ours.
--
-- That is exactly the argument this whole feature exists to settle. `credit-life-design.md`
-- §2.8 calls one-file-one-invoice "what reconciliation arguments are actually about", and a
-- reconciliation needs both sides of the disagreement, not one.
--
-- NULL where a lender sent no premium column, which is legitimate and stays legitimate: the
-- five required columns are unchanged, and a file without this one reconciles against nothing
-- rather than being refused.
ALTER TABLE policy.enrolment_submission_row
    ADD COLUMN stated_premium_amount NUMERIC(19,2);

ALTER TABLE policy.enrolment_submission_row
    ADD CONSTRAINT chk_enrolment_row_stated_premium_non_negative
        CHECK (stated_premium_amount IS NULL OR stated_premium_amount >= 0);

-- The file's own total, summed across the rows that carried one, so a variance can be read off
-- the submission without walking every row. Null when no row stated anything.
ALTER TABLE policy.enrolment_submission
    ADD COLUMN stated_premium_total NUMERIC(19,2);

ALTER TABLE policy.enrolment_submission
    ADD CONSTRAINT chk_enrolment_submission_stated_total_non_negative
        CHECK (stated_premium_total IS NULL OR stated_premium_total >= 0);
