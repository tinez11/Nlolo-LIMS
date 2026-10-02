-- db-migrations/payment/V10__deposit_maturity_purpose.sql
-- A fixed-term deposit paid out at maturity (2026-10-02). Its own purpose, not MATURITY_PAYOUT:
-- benefitpayout settles every MATURITY_PAYOUT against an instalment it owns, and a deposit has none.
ALTER TABLE payment.disbursement_instruction
    DROP CONSTRAINT disbursement_instruction_purpose_check;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_purpose_check CHECK (purpose IN
        ('LOAN_DISBURSEMENT','CLAIM_SETTLEMENT','COMMISSION_PAYOUT','SURRENDER_PAYOUT',
         'MATURITY_PAYOUT','DIVIDEND_PAYOUT',
         'SURVIVAL_BENEFIT_PAYOUT','INCOME_PAYOUT','PREMIUM_RETURN_PAYOUT','FREE_LOOK_REFUND',
         'WITHDRAWAL_PAYOUT',
         -- A fixed-term deposit paid out at maturity.
         'DEPOSIT_MATURITY_PAYOUT'));
