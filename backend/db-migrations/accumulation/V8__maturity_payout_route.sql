-- db-migrations/accumulation/V8__maturity_payout_route.sql
-- How a deposit's pay-out instruction is paid at maturity (2026-10-28, user decisions: mobile money, bank transfer or
-- cheque -- no cash; bank details as proper fields). payee_ref stays the payee: the phone, the account name, or the
-- name a cheque is made out to. Every instruction before this paid by mobile money.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE accumulation.maturity_instruction ADD COLUMN IF NOT EXISTS payout_method VARCHAR(20) NOT NULL DEFAULT 'MOBILE_MONEY';
ALTER TABLE accumulation.maturity_instruction ADD COLUMN IF NOT EXISTS payee_bank VARCHAR(100);
ALTER TABLE accumulation.maturity_instruction ADD COLUMN IF NOT EXISTS payee_branch VARCHAR(100);
ALTER TABLE accumulation.maturity_instruction ADD COLUMN IF NOT EXISTS payee_account_name VARCHAR(200);
ALTER TABLE accumulation.maturity_instruction ADD COLUMN IF NOT EXISTS payee_account_number VARCHAR(50);

ALTER TABLE accumulation.maturity_instruction DROP CONSTRAINT IF EXISTS chk_maturity_payout_method;
ALTER TABLE accumulation.maturity_instruction
    ADD CONSTRAINT chk_maturity_payout_method CHECK (payout_method IN ('MOBILE_MONEY', 'EFT', 'CHEQUE'));
