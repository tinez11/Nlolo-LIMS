-- db-migrations/distribution/V11__commission_payout_approval.sql
-- A commission payout waits for a second person (money movement step 4, 2026-11-04, design §6). Until now asking to
-- pay a statement (commission.pay) asked the rail in the same breath. The statement now rests at
-- AWAITING_PAYOUT_APPROVAL until someone holding payouts.approve releases it (or the person who asked, holding
-- payouts.approve-own, recorded in acted_alone), or refuses it, which returns it to CLOSED with the reason kept so it
-- can be asked again. status widens to 30: AWAITING_PAYOUT_APPROVAL is 24 characters.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE distribution.commission_statement ALTER COLUMN status TYPE VARCHAR(30);
ALTER TABLE distribution.commission_statement ADD COLUMN IF NOT EXISTS payout_requested_by VARCHAR(100);
ALTER TABLE distribution.commission_statement ADD COLUMN IF NOT EXISTS payout_decided_by VARCHAR(100);
ALTER TABLE distribution.commission_statement ADD COLUMN IF NOT EXISTS payout_decided_at TIMESTAMPTZ;
ALTER TABLE distribution.commission_statement ADD COLUMN IF NOT EXISTS payout_refusal_reason VARCHAR(500);
ALTER TABLE distribution.commission_statement ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE distribution.commission_statement DROP CONSTRAINT IF EXISTS commission_statement_status_check;
ALTER TABLE distribution.commission_statement ADD CONSTRAINT commission_statement_status_check CHECK (status IN
    ('OPEN','CLOSED','AWAITING_PAYOUT_APPROVAL','PAYOUT_REQUESTED','PAID','PAYOUT_FAILED'));

ALTER TABLE distribution.commission_statement DROP CONSTRAINT IF EXISTS commission_statement_payout_two_person;
ALTER TABLE distribution.commission_statement ADD CONSTRAINT commission_statement_payout_two_person
    CHECK (payout_decided_by IS NULL OR payout_requested_by IS NULL OR payout_decided_by <> payout_requested_by
           OR acted_alone);
