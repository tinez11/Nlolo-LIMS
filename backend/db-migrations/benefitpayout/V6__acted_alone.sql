-- db-migrations/benefitpayout/V6__acted_alone.sql
-- Acting alone (2026-11-04, the user: a company with one finance person must still finish its work). A person given
-- payouts.approve-own may approve a payout they reviewed, a free-look refund they requested or a withholding rule they
-- proposed; the row says so in acted_alone, and the two-person rule holds for everyone else.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS benefitpayout.payout_instalment ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS benefitpayout.payout_instalment DROP CONSTRAINT IF EXISTS payout_two_person;
ALTER TABLE IF EXISTS benefitpayout.payout_instalment
    ADD CONSTRAINT payout_two_person CHECK (approved_by IS NULL OR approved_by <> reviewed_by OR acted_alone);

ALTER TABLE IF EXISTS benefitpayout.free_look_cancellation ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS benefitpayout.free_look_cancellation DROP CONSTRAINT IF EXISTS free_look_two_person;
ALTER TABLE IF EXISTS benefitpayout.free_look_cancellation
    ADD CONSTRAINT free_look_two_person CHECK (approved_by IS NULL OR approved_by <> requested_by OR acted_alone);

ALTER TABLE IF EXISTS benefitpayout.withholding_rule ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS benefitpayout.withholding_rule DROP CONSTRAINT IF EXISTS withholding_rule_check1;
ALTER TABLE IF EXISTS benefitpayout.withholding_rule DROP CONSTRAINT IF EXISTS withholding_rule_two_person;
ALTER TABLE IF EXISTS benefitpayout.withholding_rule
    ADD CONSTRAINT withholding_rule_two_person CHECK (approved_by IS NULL OR approved_by <> proposed_by OR acted_alone);
