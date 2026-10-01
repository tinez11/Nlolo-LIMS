-- db-migrations/payment/V8__benefit_payout_purposes.sql
-- Product step 2: the payout engine's disbursement purposes.
--
-- MATURITY_PAYOUT has been an allowed value since V1 and had no publisher until now -- the
-- platform could name a maturity payment and never make one. These four give the rest of the
-- engine its own names, so a disbursement says what kind of promise it is keeping rather than
-- being filed under a purpose that merely resembles it.
--
-- The CHECK sits on the parent of a partitioned table, as V2 put it there, so it propagates to
-- every partition without touching them individually.
ALTER TABLE payment.disbursement_instruction
    DROP CONSTRAINT disbursement_instruction_purpose_check;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_purpose_check CHECK (purpose IN
        ('LOAN_DISBURSEMENT','CLAIM_SETTLEMENT','COMMISSION_PAYOUT','SURRENDER_PAYOUT',
         'MATURITY_PAYOUT','DIVIDEND_PAYOUT',
         -- Step 2: a survival benefit, an income instalment, a return of premiums on a
         -- return-of-premium term policy, and a free-look refund.
         'SURVIVAL_BENEFIT_PAYOUT','INCOME_PAYOUT','PREMIUM_RETURN_PAYOUT','FREE_LOOK_REFUND'));
