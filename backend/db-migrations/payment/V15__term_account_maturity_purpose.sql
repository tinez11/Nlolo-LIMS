-- db-migrations/payment/V15__term_account_maturity_purpose.sql
-- A term account (2026-10-21) pays its whole balance out when it matures. Its own purpose, not
-- DEPOSIT_MATURITY_PAYOUT: accumulation settles a deposit's payout against its deposit periods, and a
-- term account has none -- each purpose is answered by the code that asked for it.
ALTER TABLE payment.disbursement_instruction
    DROP CONSTRAINT disbursement_instruction_purpose_check;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_purpose_check CHECK (purpose IN
        ('LOAN_DISBURSEMENT','CLAIM_SETTLEMENT','COMMISSION_PAYOUT','SURRENDER_PAYOUT',
         'MATURITY_PAYOUT','DIVIDEND_PAYOUT',
         'SURVIVAL_BENEFIT_PAYOUT','INCOME_PAYOUT','PREMIUM_RETURN_PAYOUT','FREE_LOOK_REFUND',
         'WITHDRAWAL_PAYOUT',
         'DEPOSIT_MATURITY_PAYOUT',
         'ANNUITY_PAYOUT',
         -- A pension's lump sum at vesting.
         'COMMUTATION_PAYOUT',
         -- Unit-linked (product step 6).
         'PRICE_CORRECTION_PAYOUT','LAPSE_SURRENDER_PAYOUT','TOP_UP_REFUND',
         -- A term account at maturity.
         'TERM_ACCOUNT_MATURITY_PAYOUT'));
