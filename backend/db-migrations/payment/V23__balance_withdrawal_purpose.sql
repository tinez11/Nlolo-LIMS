-- db-migrations/payment/V23__balance_withdrawal_purpose.sql
-- Money taken back off a policy's balance (money movement step 3a, 2026-11-04): its own payout purpose, so only
-- billing takes the outcome. disbursement_instruction is partitioned; a CHECK on the parent reaches every partition.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS payment.disbursement_instruction DROP CONSTRAINT IF EXISTS disbursement_instruction_purpose_check;
ALTER TABLE IF EXISTS payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_purpose_check CHECK (purpose IN
        ('LOAN_DISBURSEMENT','CLAIM_SETTLEMENT','COMMISSION_PAYOUT','SURRENDER_PAYOUT',
         'MATURITY_PAYOUT','DIVIDEND_PAYOUT',
         'SURVIVAL_BENEFIT_PAYOUT','INCOME_PAYOUT','PREMIUM_RETURN_PAYOUT','FREE_LOOK_REFUND',
         'WITHDRAWAL_PAYOUT',
         'DEPOSIT_MATURITY_PAYOUT',
         'ANNUITY_PAYOUT',
         'COMMUTATION_PAYOUT',
         'PRICE_CORRECTION_PAYOUT','LAPSE_SURRENDER_PAYOUT','TOP_UP_REFUND',
         'TERM_ACCOUNT_MATURITY_PAYOUT',
         'CREDIT_LIFE_PREMIUM_REFUND',
         -- A payer's money taken back off a policy's balance.
         'BALANCE_WITHDRAWAL'));
