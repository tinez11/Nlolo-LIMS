-- db-migrations/payment/V18__payout_routes.sql
-- How a payout reaches the person (2026-10-28, user decisions: mobile money, bank transfer or cheque -- no cash; bank
-- details as proper fields). Until now every payout but a credit-life claim went by mobile money, so a withdrawal staff
-- meant to send to a bank went to the "phone number" they typed: the word "bank".
--
-- A bank transfer (EFT) and a cheque are both made by a person, in the bank's portal or on the cheque book: both wait
-- AWAITING_EXECUTION in finance's queue and complete when that person records the bank reference or cheque number.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE payment.disbursement_instruction DROP CONSTRAINT IF EXISTS chk_disbursement_method_known;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT chk_disbursement_method_known CHECK (method IN ('MOBILE_MONEY', 'EFT', 'CHEQUE'));

ALTER TABLE payment.disbursement_instruction DROP CONSTRAINT IF EXISTS chk_disbursement_only_eft_is_executed_by_hand;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT chk_disbursement_only_eft_is_executed_by_hand CHECK (executed_by IS NULL OR method IN ('EFT', 'CHEQUE'));

-- The account a bank transfer goes to. payee_ref keeps the account name, so every list that already shows a payee
-- still shows someone meaningful.
ALTER TABLE payment.disbursement_instruction ADD COLUMN IF NOT EXISTS payee_bank VARCHAR(100);
ALTER TABLE payment.disbursement_instruction ADD COLUMN IF NOT EXISTS payee_branch VARCHAR(100);
ALTER TABLE payment.disbursement_instruction ADD COLUMN IF NOT EXISTS payee_account_name VARCHAR(200);
ALTER TABLE payment.disbursement_instruction ADD COLUMN IF NOT EXISTS payee_account_number VARCHAR(50);

ALTER TABLE payment.disbursement_instruction DROP CONSTRAINT IF EXISTS chk_disbursement_bank_details;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT chk_disbursement_bank_details CHECK (
        method <> 'EFT' OR payee_account_number IS NULL
        OR (payee_bank IS NOT NULL AND payee_account_name IS NOT NULL));
