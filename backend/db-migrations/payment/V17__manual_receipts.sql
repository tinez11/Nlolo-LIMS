-- db-migrations/payment/V17__manual_receipts.sql
-- Payment methods and manual receipting (2026-10-27, user decisions: every method receipted by hand for now; finance
-- records, a second finance person approves).
--
-- Until now money came in one way only: the mobile-money gateway pulled it. Cash at a branch, a bank deposit, a
-- cheque, a payment to the insurer's Lipa number or a TANQR scan reached the platform nowhere -- the premium stayed
-- unpaid in the books however it had been paid in fact. A manual receipt is a collection like any other, in the same
-- ledger, so the register, the invoice and the general ledger all see it the same way; what differs is how it was
-- paid, who recorded it, and that a second person confirms it before it counts.

-- How the money came in. MOBILE_MONEY is the gateway's, and every row before this.
ALTER TABLE payment.payment_transaction
    ADD COLUMN IF NOT EXISTS method VARCHAR(20) NOT NULL DEFAULT 'MOBILE_MONEY';
ALTER TABLE payment.payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_method_check;
ALTER TABLE payment.payment_transaction
    ADD CONSTRAINT payment_transaction_method_check
    CHECK (method IN ('MOBILE_MONEY','CASH','BANK_DEPOSIT','CHEQUE','LIPA_NUMBER','TANQR'));

-- The record a person makes of money the platform did not pull itself.
ALTER TABLE payment.payment_transaction ADD COLUMN IF NOT EXISTS received_on DATE;
ALTER TABLE payment.payment_transaction ADD COLUMN IF NOT EXISTS recorded_by VARCHAR(100);
ALTER TABLE payment.payment_transaction ADD COLUMN IF NOT EXISTS note VARCHAR(500);
ALTER TABLE payment.payment_transaction ADD COLUMN IF NOT EXISTS field_receipt_id UUID;
ALTER TABLE payment.payment_transaction ADD COLUMN IF NOT EXISTS decided_by VARCHAR(100);
ALTER TABLE payment.payment_transaction ADD COLUMN IF NOT EXISTS decided_at TIMESTAMPTZ;
ALTER TABLE payment.payment_transaction ADD COLUMN IF NOT EXISTS decision_reason VARCHAR(500);

-- 17 characters, so the column widens from V1's 15 first; on the partitioned parent it reaches every partition.
ALTER TABLE payment.payment_transaction ALTER COLUMN status TYPE VARCHAR(20);

-- AWAITING_APPROVAL: recorded, not yet confirmed by a second person -- nothing is paid until it is.
-- REJECTED: the second person found it wrong; it never paid anything.
ALTER TABLE payment.payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_status_check;
ALTER TABLE payment.payment_transaction
    ADD CONSTRAINT payment_transaction_status_check CHECK (status IN
        ('PENDING','IN_DOUBT','CONFIRMED','FAILED','AWAITING_APPROVAL','REJECTED'));

-- Two people, in the database too: whoever recorded a receipt cannot be the one who decides it.
ALTER TABLE payment.payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_receipt_two_person;
ALTER TABLE payment.payment_transaction
    ADD CONSTRAINT payment_transaction_receipt_two_person
    CHECK (decided_by IS NULL OR recorded_by IS NULL OR decided_by <> recorded_by);

-- A manual receipt names its method's own reference (a bank slip, a cheque number, a Lipa transaction id), which is
-- what stops the same slip being recorded twice. Not unique in the table -- a partitioned parent cannot hold a unique
-- index without its partition key -- so the application asks this index before recording.
CREATE INDEX IF NOT EXISTS idx_payment_transaction_receipt_reference
    ON payment.payment_transaction (tenant_id, method, gateway_reference)
    WHERE method <> 'MOBILE_MONEY';
