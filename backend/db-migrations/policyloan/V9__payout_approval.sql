-- db-migrations/policyloan/V9__payout_approval.sql
-- A loan's payout waits for a second person (money movement step 4, 2026-11-04, design §6). Until now taking a loan
-- (loans.manage) asked the rail to pay it in the same breath -- the one money-out road with nobody checking it. The
-- loan now rests at AWAITING_PAYOUT_APPROVAL until someone holding payouts.approve releases it (or the person who took
-- it, holding payouts.approve-own, recorded in acted_alone), or refuses it, which frees the reserved loan value
-- (PAYOUT_REFUSED). The payee is kept on the loan so the approver sees whom it pays and the request carries it.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE policyloan.policy_loan ADD COLUMN IF NOT EXISTS payee_ref VARCHAR(200);
ALTER TABLE policyloan.policy_loan ADD COLUMN IF NOT EXISTS payout_decided_by VARCHAR(100);
ALTER TABLE policyloan.policy_loan ADD COLUMN IF NOT EXISTS payout_decided_at TIMESTAMPTZ;
ALTER TABLE policyloan.policy_loan ADD COLUMN IF NOT EXISTS payout_refusal_reason VARCHAR(500);
ALTER TABLE policyloan.policy_loan ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE policyloan.policy_loan DROP CONSTRAINT IF EXISTS policy_loan_status_check;
ALTER TABLE policyloan.policy_loan ADD CONSTRAINT policy_loan_status_check CHECK (status IN
    ('RESERVED_PENDING_ORIGINATION','ORIGINATED','AWAITING_PAYOUT_APPROVAL','PAYOUT_REFUSED',
     'DISBURSEMENT_REQUESTED','DISBURSED','REPAYING','SETTLED','FORCED_LAPSE_TRIGGERED','DISBURSEMENT_FAILED'));

ALTER TABLE policyloan.policy_loan DROP CONSTRAINT IF EXISTS policy_loan_payout_two_person;
ALTER TABLE policyloan.policy_loan ADD CONSTRAINT policy_loan_payout_two_person
    CHECK (payout_decided_by IS NULL OR created_by IS NULL OR payout_decided_by <> created_by OR acted_alone);
