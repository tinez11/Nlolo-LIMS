-- db-migrations/benefitpayout/V4__withholding_rule_end.sql
-- Product step 5 (D1): an APPROVED withholding rule can be ended, by one finance officer (the user's
-- decision, 2026-10-03). Ending sets effective_to; these columns record who did it and when. Before
-- this, an approved rule could never end, and approving its replacement was refused as an overlap with
-- advice -- "give that one an end date first" -- that nothing could act on.
ALTER TABLE benefitpayout.withholding_rule
    ADD COLUMN ended_by VARCHAR(100),
    ADD COLUMN ended_at TIMESTAMPTZ,
    -- Only an approved rule is ended, and an ending always has its date.
    ADD CONSTRAINT chk_withholding_rule_end CHECK (ended_by IS NULL OR (status = 'APPROVED' AND effective_to IS NOT NULL));
