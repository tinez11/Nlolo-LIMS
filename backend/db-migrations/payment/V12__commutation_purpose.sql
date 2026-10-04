-- db-migrations/payment/V12__commutation_purpose.sql
-- A pension's lump sum at vesting (product step 5 D2). Its own purpose, so a payment can be traced
-- to the lump sum it pays and benefitpayout settles it against the instalment it owns.
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
         'COMMUTATION_PAYOUT'));
