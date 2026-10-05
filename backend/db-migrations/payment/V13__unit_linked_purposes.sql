-- db-migrations/payment/V13__unit_linked_purposes.sql
-- Product step 6 (U1): two unit-linked payouts with their own purposes, so each payment traces to what it pays.
--   PRICE_CORRECTION_PAYOUT  -- the difference a price correction found owed on a payout already made (spec §3)
--   LAPSE_SURRENDER_PAYOUT   -- the units of a policy lapsed for non-payment, sold and paid to the policyholder (spec §6)
-- A unit-linked surrender, maturity and free-look reuse SURRENDER_PAYOUT, MATURITY_PAYOUT and FREE_LOOK_REFUND.
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
         'PRICE_CORRECTION_PAYOUT','LAPSE_SURRENDER_PAYOUT'));
