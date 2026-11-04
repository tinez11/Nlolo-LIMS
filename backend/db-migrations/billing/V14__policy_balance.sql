-- db-migrations/billing/V14__policy_balance.sql
-- The policy balance (money movement step 2, 2026-11-04, user decisions D1-D3, D6). Every confirmed premium deposit
-- lands on its policy's balance first; billing then pays the policy's open invoices from it, oldest first, and what is
-- left stays and pays the next premium when it is raised. Before this, a receipt was tied to one invoice and refused
-- for more than it owed, a part payment never reached the ledger, and money confirmed after an invoice was settled
-- was kept nowhere.
--
-- policy_balance  one row per policy that has ever received a deposit: the lock the allocator takes, so two deposits
--                 at once cannot pay the same invoice twice. The balance itself is the sum of the entries.
-- balance_entry   add-only: DEPOSIT in, PREMIUM_PAID out (step 3 adds WITHDRAWAL and REVERSAL). Nothing is updated or
--                 deleted -- the entries are the policy's statement and the auditor's trail.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

CREATE TABLE IF NOT EXISTS billing.policy_balance (
    tenant_id     UUID        NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    currency      CHAR(3)     NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, policy_number)
);

CREATE TABLE IF NOT EXISTS billing.balance_entry (
    entry_id      UUID          PRIMARY KEY,
    tenant_id     UUID          NOT NULL,
    policy_number VARCHAR(20)   NOT NULL,
    entry_type    VARCHAR(20)   NOT NULL CHECK (entry_type IN ('DEPOSIT', 'PREMIUM_PAID')),
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency      CHAR(3)       NOT NULL,
    -- The confirmed payment a DEPOSIT came from (payment_transaction id); the invoice a PREMIUM_PAID paid.
    payment_id    UUID,
    invoice_id    UUID,
    method        VARCHAR(20),
    reference     VARCHAR(100),
    -- Where a DEPOSIT came from: PAYMENT (a confirmed deposit or mobile-money request) or LOAN_SURPLUS (money above
    -- what a loan owed, already posted by policyloan's own rule E-03-SURPLUS).
    source        VARCHAR(20)   NOT NULL DEFAULT 'PAYMENT' CHECK (source IN ('PAYMENT', 'LOAN_SURPLUS')),
    created_by    VARCHAR(100),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_balance_entry_shape CHECK (
        (entry_type = 'DEPOSIT' AND invoice_id IS NULL)
        OR (entry_type = 'PREMIUM_PAID' AND invoice_id IS NOT NULL AND payment_id IS NULL))
);
-- A redelivered confirmation deposits once.
CREATE UNIQUE INDEX IF NOT EXISTS ux_balance_entry_deposit_payment
    ON billing.balance_entry (tenant_id, payment_id) WHERE entry_type = 'DEPOSIT' AND payment_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_balance_entry_policy ON billing.balance_entry (tenant_id, policy_number, created_at);

ALTER TABLE billing.policy_balance ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS policy_balance_tenant_isolation ON billing.policy_balance;
CREATE POLICY policy_balance_tenant_isolation ON billing.policy_balance
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE billing.balance_entry ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS balance_entry_tenant_isolation ON billing.balance_entry;
CREATE POLICY balance_entry_tenant_isolation ON billing.balance_entry
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON billing.policy_balance TO app_role;
GRANT SELECT, INSERT ON billing.balance_entry TO app_role;

-- Policies whose balance can pay a premium that has fallen due, across every tenant, for BalanceAllocationDrain.
-- Invoices are raised a year ahead, so a balance waits for each premium's due date rather than paying next year's
-- premiums the day the money lands. The day is Dar es Salaam's, not the database's UTC one. SECURITY DEFINER for the
-- reason schedules_due_for_invoicing is: a background thread has no tenant, so RLS would show it nothing.
CREATE OR REPLACE FUNCTION billing.balances_due_for_allocation()
RETURNS TABLE (tenant_id UUID, policy_number VARCHAR)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT b.tenant_id, b.policy_number
      FROM billing.policy_balance b
     WHERE (SELECT COALESCE(SUM(CASE WHEN e.entry_type = 'DEPOSIT' THEN e.amount ELSE -e.amount END), 0)
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
