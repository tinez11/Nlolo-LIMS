-- A credit-life borrower's unearned premium paid back to the lender (2026-10-30, the user: pay it back, option b).
-- A credit raised after the lender had paid its file was recorded and never settled -- money owed to the lender with
-- nothing to pay it. The part of a credit the lender had already paid is now paid back by bank transfer, which finance
-- makes by hand, as a credit-life claim is. A credit on a file not yet paid only lowers what is owed, as before.
--
-- refund_amount      the part of the credit paid back in money; null when the credit only lowered what was owed
-- refund_payee       the lender, by name, for finance to know whose account
-- refund_status      REQUESTED until the transfer is made, then PAID; FAILED if the rail refused it
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE billing.premium_credit
    ADD COLUMN IF NOT EXISTS refund_amount          NUMERIC(19,2),
    ADD COLUMN IF NOT EXISTS refund_payee           VARCHAR(255),
    ADD COLUMN IF NOT EXISTS refund_status          VARCHAR(20),
    ADD COLUMN IF NOT EXISTS refund_disbursement_id UUID,
    ADD COLUMN IF NOT EXISTS refund_paid_at         TIMESTAMPTZ;

ALTER TABLE billing.premium_credit DROP CONSTRAINT IF EXISTS chk_premium_credit_refund;
ALTER TABLE billing.premium_credit ADD CONSTRAINT chk_premium_credit_refund CHECK (
    (refund_amount IS NULL AND refund_status IS NULL)
    OR (refund_amount > 0 AND refund_amount <= amount AND refund_status IN ('REQUESTED', 'PAID', 'FAILED')));

GRANT UPDATE ON billing.premium_credit TO app_role;
