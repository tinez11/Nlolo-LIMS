-- db-migrations/payment/V9__account_purposes.sql
-- Product step 3: money moving in and out of a savings account.
--
-- A collection has had no purpose until now, because every collection was a premium and billing
-- parsed every confirmed sourceRef as an invoice id. A top-up is a collection that is NOT a
-- premium, so collections gain the purpose disbursements have always had. Defaulted to PREMIUM:
-- every existing row, and every collection billing requests, keeps its meaning.
--
-- payment_transaction is partitioned; a column with a constant default added on the parent
-- propagates to every partition, as V2's CHECKs do.
ALTER TABLE payment.payment_transaction
    ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'PREMIUM'
        CONSTRAINT payment_transaction_purpose_check CHECK (purpose IN ('PREMIUM','ACCOUNT_TOP_UP'));

ALTER TABLE payment.disbursement_instruction
    DROP CONSTRAINT disbursement_instruction_purpose_check;
ALTER TABLE payment.disbursement_instruction
    ADD CONSTRAINT disbursement_instruction_purpose_check CHECK (purpose IN
        ('LOAN_DISBURSEMENT','CLAIM_SETTLEMENT','COMMISSION_PAYOUT','SURRENDER_PAYOUT',
         'MATURITY_PAYOUT','DIVIDEND_PAYOUT',
         'SURVIVAL_BENEFIT_PAYOUT','INCOME_PAYOUT','PREMIUM_RETURN_PAYOUT','FREE_LOOK_REFUND',
         -- Step 3: a partial withdrawal from a savings account.
         'WITHDRAWAL_PAYOUT'));
