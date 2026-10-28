-- db-migrations/accumulation/V6__withdrawal_payout_route.sql
-- How a withdrawal is paid (2026-10-28, user decisions: mobile money, bank transfer or cheque -- no cash; bank details
-- as proper fields). Before this every withdrawal went by mobile money to payee_ref, so staff who meant a bank sent the
-- money to the "phone number" they typed: the word "bank". payee_ref stays the payee: the phone on mobile money, the
-- account name on a transfer, the name a cheque is made out to.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE accumulation.withdrawal_request ADD COLUMN IF NOT EXISTS method VARCHAR(20) NOT NULL DEFAULT 'MOBILE_MONEY';
ALTER TABLE accumulation.withdrawal_request ADD COLUMN IF NOT EXISTS payee_bank VARCHAR(100);
ALTER TABLE accumulation.withdrawal_request ADD COLUMN IF NOT EXISTS payee_branch VARCHAR(100);
ALTER TABLE accumulation.withdrawal_request ADD COLUMN IF NOT EXISTS payee_account_name VARCHAR(200);
ALTER TABLE accumulation.withdrawal_request ADD COLUMN IF NOT EXISTS payee_account_number VARCHAR(50);

ALTER TABLE accumulation.withdrawal_request DROP CONSTRAINT IF EXISTS chk_withdrawal_method_known;
ALTER TABLE accumulation.withdrawal_request
    ADD CONSTRAINT chk_withdrawal_method_known CHECK (method IN ('MOBILE_MONEY', 'EFT', 'CHEQUE'));

ALTER TABLE accumulation.withdrawal_request DROP CONSTRAINT IF EXISTS chk_withdrawal_bank_details;
ALTER TABLE accumulation.withdrawal_request
    ADD CONSTRAINT chk_withdrawal_bank_details CHECK (
        method <> 'EFT' OR (payee_bank IS NOT NULL AND payee_account_name IS NOT NULL AND payee_account_number IS NOT NULL));
