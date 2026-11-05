-- db-migrations/billing/V16__deposit_reversal.sql
-- Reversing a deposit whose money never really arrived -- a bounced cheque, a reversed mobile-money payment (money
-- movement step 3b, 2026-11-04). One person asks (collections.manage), a second confirms (collections.approve), or
-- the same person holding collections.approve-own, recorded in acted_alone. On confirmation the deposit comes off the
-- balance (a REVERSAL entry); if that leaves the balance below nothing, the premiums it paid reopen, newest first (a
-- PREMIUM_REOPENED entry each, linked to the PREMIUM_PAID it undoes), and their commission is clawed back (user
-- decision A, 2026-11-04). Money already withdrawn cannot be reopened: the balance then stays below zero and reads
-- "Owes".
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

CREATE TABLE IF NOT EXISTS billing.deposit_reversal (
    reversal_id      UUID          PRIMARY KEY,
    tenant_id        UUID          NOT NULL,
    policy_number    VARCHAR(20)   NOT NULL,
    payment_id       UUID          NOT NULL,
    amount           NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency         CHAR(3)       NOT NULL,
    reason           VARCHAR(500)  NOT NULL,
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('REQUESTED', 'CONFIRMED', 'REJECTED')),
    requested_by     VARCHAR(100)  NOT NULL,
    requested_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_by       VARCHAR(100),
    decided_at       TIMESTAMPTZ,
    rejection_reason VARCHAR(500),
    acted_alone      BOOLEAN       NOT NULL DEFAULT false,
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT deposit_reversal_two_person CHECK (decided_by IS NULL OR decided_by <> requested_by OR acted_alone)
);
-- A deposit is reversed once, and only one request is open on it at a time.
CREATE UNIQUE INDEX IF NOT EXISTS ux_deposit_reversal_live ON billing.deposit_reversal (tenant_id, payment_id)
    WHERE status IN ('REQUESTED', 'CONFIRMED');
CREATE INDEX IF NOT EXISTS idx_deposit_reversal_status ON billing.deposit_reversal (tenant_id, status, requested_at);

ALTER TABLE billing.deposit_reversal ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS deposit_reversal_tenant_isolation ON billing.deposit_reversal;
CREATE POLICY deposit_reversal_tenant_isolation ON billing.deposit_reversal
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON billing.deposit_reversal TO app_role;

-- The balance's entries gain the reversal (out) and a premium reopened (back in), linked to what each undoes.
ALTER TABLE billing.balance_entry ADD COLUMN IF NOT EXISTS reverses_entry_id UUID;
ALTER TABLE billing.balance_entry DROP CONSTRAINT IF EXISTS balance_entry_entry_type_check;
ALTER TABLE billing.balance_entry ADD CONSTRAINT balance_entry_entry_type_check
    CHECK (entry_type IN ('DEPOSIT', 'PREMIUM_PAID', 'WITHDRAWAL', 'REVERSAL', 'PREMIUM_REOPENED'));
ALTER TABLE billing.balance_entry DROP CONSTRAINT IF EXISTS chk_balance_entry_shape;
ALTER TABLE billing.balance_entry ADD CONSTRAINT chk_balance_entry_shape CHECK (
    (entry_type = 'DEPOSIT' AND invoice_id IS NULL)
    OR (entry_type = 'PREMIUM_PAID' AND invoice_id IS NOT NULL AND payment_id IS NULL)
    OR (entry_type = 'WITHDRAWAL' AND withdrawal_id IS NOT NULL AND invoice_id IS NULL AND payment_id IS NULL)
    OR (entry_type = 'REVERSAL' AND reverses_entry_id IS NOT NULL AND invoice_id IS NULL)
    OR (entry_type = 'PREMIUM_REOPENED' AND reverses_entry_id IS NOT NULL AND invoice_id IS NOT NULL));
-- A deposit reverses once.
CREATE UNIQUE INDEX IF NOT EXISTS ux_balance_entry_reversal
    ON billing.balance_entry (tenant_id, reverses_entry_id) WHERE entry_type = 'REVERSAL';

-- How many times an invoice was reopened after being paid: the n-th payment in full is a new collection, so the
-- commission booked on it is keyed apart from the one clawed back.
ALTER TABLE billing.premium_invoice ADD COLUMN IF NOT EXISTS reopened_count INTEGER NOT NULL DEFAULT 0;

-- Balances count every entry by its direction: in = DEPOSIT, PREMIUM_REOPENED; out = everything else.
CREATE OR REPLACE FUNCTION billing.balances_due_for_allocation()
RETURNS TABLE (tenant_id UUID, policy_number VARCHAR)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT b.tenant_id, b.policy_number
      FROM billing.policy_balance b
     WHERE (SELECT COALESCE(SUM(CASE WHEN e.entry_type IN ('DEPOSIT', 'PREMIUM_REOPENED') THEN e.amount
                                     ELSE -e.amount END), 0)
              FROM billing.balance_entry e
             WHERE e.tenant_id = b.tenant_id AND e.policy_number = b.policy_number) > 0
       AND EXISTS (SELECT 1 FROM billing.premium_invoice i
                    WHERE i.tenant_id = b.tenant_id AND i.policy_number = b.policy_number
                      AND i.status IN ('DUE', 'IN_GRACE', 'OVERDUE', 'PARTIALLY_PAID')
                      AND i.due_date <= (now() AT TIME ZONE 'Africa/Dar_es_Salaam')::date)
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION billing.balances_due_for_allocation() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing.balances_due_for_allocation() TO app_role;
