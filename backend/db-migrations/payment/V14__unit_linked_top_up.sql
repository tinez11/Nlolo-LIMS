-- db-migrations/payment/V14__unit_linked_top_up.sql
-- Product step 6 (U2): a unit-linked top-up is a collection that is neither a premium nor a savings top-up. Its own
-- purpose, so billing (PREMIUM) and accumulation (ACCOUNT_TOP_UP) each keep ignoring money that is not theirs, and
-- unitlinked takes only UL_TOP_UP. payment_transaction is partitioned; a CHECK on the parent reaches every partition.
ALTER TABLE payment.payment_transaction DROP CONSTRAINT payment_transaction_purpose_check;
ALTER TABLE payment.payment_transaction
    ADD CONSTRAINT payment_transaction_purpose_check CHECK (purpose IN ('PREMIUM','ACCOUNT_TOP_UP','UL_TOP_UP'));

-- A top-up refunded whole because the policy ended before its money arrived. Not PREMIUM_RETURN_PAYOUT: benefitpayout
-- closes an instalment on every disbursement of that purpose and would look for a top-up among its instalments.
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
         'PRICE_CORRECTION_PAYOUT','LAPSE_SURRENDER_PAYOUT','TOP_UP_REFUND'));
