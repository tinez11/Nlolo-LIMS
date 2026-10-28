-- db-migrations/payment/V19__top_up_one_person.sql
-- A savings top-up needs no second person (2026-10-28, user decision). Money received by hand for an account is
-- receipted by the person who records it, and a mobile-money top-up may be confirmed by whoever asked for it. Premium
-- receipts keep their two signatures.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE payment.payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_receipt_two_person;
ALTER TABLE payment.payment_transaction
    ADD CONSTRAINT payment_transaction_receipt_two_person
    CHECK (decided_by IS NULL OR recorded_by IS NULL OR decided_by <> recorded_by OR purpose = 'ACCOUNT_TOP_UP');
