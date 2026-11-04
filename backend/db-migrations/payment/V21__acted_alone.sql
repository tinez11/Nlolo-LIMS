-- db-migrations/payment/V21__acted_alone.sql
-- Acting alone (2026-11-04, the user: a company with one finance person must still finish its work). A person given
-- collections.approve-own may confirm or refuse a receipt they recorded; the row says so in acted_alone, and the
-- two-person rule holds for everyone else. A savings top-up already needed one person (V19) and still does.
-- On the partitioned parent, so every partition, present and future, carries the same column and rule.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS payment.payment_transaction ADD COLUMN IF NOT EXISTS acted_alone BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE IF EXISTS payment.payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_receipt_two_person;
ALTER TABLE IF EXISTS payment.payment_transaction
    ADD CONSTRAINT payment_transaction_receipt_two_person
    CHECK (decided_by IS NULL OR recorded_by IS NULL OR decided_by <> recorded_by OR purpose = 'ACCOUNT_TOP_UP'
           OR acted_alone);
