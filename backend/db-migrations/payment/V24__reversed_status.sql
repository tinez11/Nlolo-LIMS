-- db-migrations/payment/V24__reversed_status.sql
-- A confirmed receipt whose money never really arrived -- a bounced cheque, a reversed mobile-money payment -- is
-- REVERSED once billing's reversal is confirmed (money movement step 3b, 2026-11-04). The register then says so
-- instead of showing money that left again as CONFIRMED.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE IF EXISTS payment.payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_status_check;
ALTER TABLE IF EXISTS payment.payment_transaction
    ADD CONSTRAINT payment_transaction_status_check CHECK (status IN
        ('PENDING','IN_DOUBT','CONFIRMED','FAILED','AWAITING_APPROVAL','REJECTED','REVERSED'));
