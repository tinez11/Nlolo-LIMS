-- db-migrations/payment/V22__loan_repayment_purpose.sql
-- Loan repayments through deposits (money movement step 1, 2026-11-04). A loan repayment is a collection like a
-- premium: requested from the payer's phone or recorded by hand and confirmed by a second person, under its own
-- purpose so billing (PREMIUM), accumulation (ACCOUNT_TOP_UP) and unitlinked (UL_TOP_UP) keep ignoring it and only
-- policyloan takes it. payment_transaction is partitioned; a CHECK on the parent reaches every partition.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS payment.payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_purpose_check;
ALTER TABLE IF EXISTS payment.payment_transaction
    ADD CONSTRAINT payment_transaction_purpose_check
    CHECK (purpose IN ('PREMIUM','ACCOUNT_TOP_UP','UL_TOP_UP','LOAN_REPAYMENT'));
