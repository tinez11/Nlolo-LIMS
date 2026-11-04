-- db-migrations/unitlinked/V5__acted_alone.sql
-- Acting alone (2026-11-04, the user: a company with one person in a department must still finish its work). A person
-- given funds.approve-own may approve a fund price they posted or settle a price correction they proposed; one given
-- accounts.approve-own a unit withdrawal they requested. The row says so in acted_alone, and the two-person rule holds
-- for everyone else.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS unitlinked.fund_price ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS unitlinked.fund_price DROP CONSTRAINT IF EXISTS fund_price_check;
ALTER TABLE IF EXISTS unitlinked.fund_price DROP CONSTRAINT IF EXISTS fund_price_two_person;
ALTER TABLE IF EXISTS unitlinked.fund_price
    ADD CONSTRAINT fund_price_two_person CHECK (approved_by IS NULL OR approved_by <> proposed_by OR acted_alone);

ALTER TABLE IF EXISTS unitlinked.price_correction_adjustment ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS unitlinked.price_correction_adjustment DROP CONSTRAINT IF EXISTS price_correction_adjustment_check;
ALTER TABLE IF EXISTS unitlinked.price_correction_adjustment DROP CONSTRAINT IF EXISTS price_correction_adjustment_two_person;
ALTER TABLE IF EXISTS unitlinked.price_correction_adjustment
    ADD CONSTRAINT price_correction_adjustment_two_person
    CHECK (decided_by IS NULL OR proposed_by IS NULL OR decided_by <> proposed_by OR acted_alone);

ALTER TABLE IF EXISTS unitlinked.withdrawal_request ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS unitlinked.withdrawal_request DROP CONSTRAINT IF EXISTS withdrawal_request_check;
ALTER TABLE IF EXISTS unitlinked.withdrawal_request DROP CONSTRAINT IF EXISTS withdrawal_request_two_person;
ALTER TABLE IF EXISTS unitlinked.withdrawal_request
    ADD CONSTRAINT withdrawal_request_two_person CHECK (approved_by IS NULL OR approved_by <> requested_by OR acted_alone);
