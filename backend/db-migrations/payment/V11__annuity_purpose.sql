-- db-migrations/payment/V11__annuity_purpose.sql
-- An annuity instalment paid out (product step 5). Its own purpose, so a payment can be traced to
-- the annuity it pays and benefitpayout settles it against the instalment it owns.
ALTER TABLE payment.disbursement_instruction
    DROP CONSTRAINT disbursement_instruction_purpose_check;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_purpose_check CHECK (purpose IN
        ('LOAN_DISBURSEMENT','CLAIM_SETTLEMENT','COMMISSION_PAYOUT','SURRENDER_PAYOUT',
         'MATURITY_PAYOUT','DIVIDEND_PAYOUT',
         'SURVIVAL_BENEFIT_PAYOUT','INCOME_PAYOUT','PREMIUM_RETURN_PAYOUT','FREE_LOOK_REFUND',
         'WITHDRAWAL_PAYOUT',
         'DEPOSIT_MATURITY_PAYOUT',
         -- An annuity instalment.
         'ANNUITY_PAYOUT'));
