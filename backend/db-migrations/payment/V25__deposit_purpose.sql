-- db-migrations/payment/V25__deposit_purpose.sql
-- Money movement step 5 (design 7.2): Record deposit names a policy, not an invoice. A deposit recorded that way is a
-- collection of its own purpose, DEPOSIT -- billing puts it on the policy's balance by its policy number, where a
-- PREMIUM collection's sourceRef is the invoice it was asked for. Every existing row keeps its purpose.
ALTER TABLE IF EXISTS payment.payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_purpose_check;
ALTER TABLE IF EXISTS payment.payment_transaction
    ADD CONSTRAINT payment_transaction_purpose_check
    CHECK (purpose IN ('PREMIUM','ACCOUNT_TOP_UP','UL_TOP_UP','LOAN_REPAYMENT','DEPOSIT'));
