-- db-migrations/policy/V42__surrender_payout_route.sql
-- How a surrender is paid (2026-10-28, user decisions: mobile money, bank transfer or cheque -- no cash; bank details
-- as proper fields). payee_ref stays the payee: the phone on mobile money, the account name on a transfer, the name a
-- cheque is made out to. Every surrender before this went by mobile money.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE policy.surrender_request ADD COLUMN IF NOT EXISTS payout_method VARCHAR(20) NOT NULL DEFAULT 'MOBILE_MONEY';
ALTER TABLE policy.surrender_request ADD COLUMN IF NOT EXISTS payee_bank VARCHAR(100);
ALTER TABLE policy.surrender_request ADD COLUMN IF NOT EXISTS payee_branch VARCHAR(100);
ALTER TABLE policy.surrender_request ADD COLUMN IF NOT EXISTS payee_account_name VARCHAR(200);
ALTER TABLE policy.surrender_request ADD COLUMN IF NOT EXISTS payee_account_number VARCHAR(50);

ALTER TABLE policy.surrender_request DROP CONSTRAINT IF EXISTS chk_surrender_payout_method;
ALTER TABLE policy.surrender_request
    ADD CONSTRAINT chk_surrender_payout_method CHECK (payout_method IN ('MOBILE_MONEY', 'EFT', 'CHEQUE'));
