-- db-migrations/billing/V17__lender_refund_approval.sql
-- A credit-life lender's premium refund waits for a second person (money movement step 4, 2026-11-04, design §6).
-- The refund is still raised automatically when a borrower leaves after the lender paid (V13), but it now rests at
-- AWAITING_APPROVAL until someone holding payouts.approve releases it to the bank-transfer queue (REQUESTED), or
-- refuses it, saying why (REFUSED). A refused refund stays owed to the lender on the books, as a failed one does,
-- until it is paid another way.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE billing.premium_credit
    ADD COLUMN IF NOT EXISTS refund_decided_by      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS refund_decided_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS refund_refusal_reason  VARCHAR(500);

ALTER TABLE billing.premium_credit DROP CONSTRAINT IF EXISTS chk_premium_credit_refund;
ALTER TABLE billing.premium_credit ADD CONSTRAINT chk_premium_credit_refund CHECK (
    (refund_amount IS NULL AND refund_status IS NULL)
    OR (refund_amount > 0 AND refund_amount <= amount
        AND refund_status IN ('AWAITING_APPROVAL', 'REQUESTED', 'PAID', 'FAILED', 'REFUSED')));
