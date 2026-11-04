-- db-migrations/billing/V15__balance_withdrawal.sql
-- Withdrawals from a policy's balance (money movement step 3a, 2026-11-04). The payer's own money comes back off the
-- balance by the payout road: one person asks (payouts.manage), a second approves (payouts.approve) or the same
-- person by permission (payouts.approve-own, recorded in acted_alone), then the payment rail pays it. When a policy
-- ends with money on its balance, a withdrawal is raised for all of it and waits for finance to name the payee
-- (AWAITING_PAYEE) -- billing does not know who should receive it once the policy has ended, least of all on a death.
--
-- The money leaves the balance at approval (a WITHDRAWAL entry); a transfer the rail refuses puts it back
-- (a DEPOSIT entry whose source is WITHDRAWAL_RETURNED, posted by the failure's own rule, not as fresh money).
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

CREATE TABLE IF NOT EXISTS billing.balance_withdrawal (
    withdrawal_id        UUID          PRIMARY KEY,
    tenant_id            UUID          NOT NULL,
    policy_number        VARCHAR(20)   NOT NULL,
    amount               NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency             CHAR(3)       NOT NULL,
    status               VARCHAR(20)   NOT NULL CHECK (status IN
                             ('AWAITING_PAYEE', 'REQUESTED', 'APPROVED', 'PAID', 'FAILED', 'REJECTED')),
    -- Why it was raised: ASKED (someone asked for the money back) or POLICY_ENDED (raised by the platform).
    reason               VARCHAR(20)   NOT NULL CHECK (reason IN ('ASKED', 'POLICY_ENDED')),
    method               VARCHAR(20),
    payee_ref            VARCHAR(200),
    payee_bank           VARCHAR(100),
    payee_branch         VARCHAR(100),
    payee_account_name   VARCHAR(200),
    payee_account_number VARCHAR(50),
    requested_by         VARCHAR(100)  NOT NULL,
    requested_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    approved_by          VARCHAR(100),
    approved_at          TIMESTAMPTZ,
    rejected_by          VARCHAR(100),
    rejected_at          TIMESTAMPTZ,
    rejection_reason     VARCHAR(500),
    disbursement_id      UUID,
    acted_alone          BOOLEAN       NOT NULL DEFAULT false,
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT balance_withdrawal_two_person CHECK (approved_by IS NULL OR approved_by <> requested_by OR acted_alone),
    CONSTRAINT balance_withdrawal_payee CHECK (status = 'AWAITING_PAYEE' OR status = 'REJECTED' OR payee_ref IS NOT NULL)
);
-- One withdrawal in flight per policy, as the savings account has (ux_withdrawal_live).
CREATE UNIQUE INDEX IF NOT EXISTS ux_balance_withdrawal_live ON billing.balance_withdrawal (tenant_id, policy_number)
    WHERE status IN ('AWAITING_PAYEE', 'REQUESTED', 'APPROVED');
CREATE INDEX IF NOT EXISTS idx_balance_withdrawal_status ON billing.balance_withdrawal (tenant_id, status, requested_at);

ALTER TABLE billing.balance_withdrawal ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS balance_withdrawal_tenant_isolation ON billing.balance_withdrawal;
CREATE POLICY balance_withdrawal_tenant_isolation ON billing.balance_withdrawal
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON billing.balance_withdrawal TO app_role;

-- The balance's entries gain the money going out, and the money coming back when a transfer fails.
ALTER TABLE billing.balance_entry DROP CONSTRAINT IF EXISTS balance_entry_entry_type_check;
ALTER TABLE billing.balance_entry ADD CONSTRAINT balance_entry_entry_type_check
    CHECK (entry_type IN ('DEPOSIT', 'PREMIUM_PAID', 'WITHDRAWAL'));
ALTER TABLE billing.balance_entry DROP CONSTRAINT IF EXISTS balance_entry_source_check;
ALTER TABLE billing.balance_entry ADD CONSTRAINT balance_entry_source_check
    CHECK (source IN ('PAYMENT', 'LOAN_SURPLUS', 'WITHDRAWAL_RETURNED'));
ALTER TABLE billing.balance_entry ADD COLUMN IF NOT EXISTS withdrawal_id UUID;
ALTER TABLE billing.balance_entry DROP CONSTRAINT IF EXISTS chk_balance_entry_shape;
ALTER TABLE billing.balance_entry ADD CONSTRAINT chk_balance_entry_shape CHECK (
    (entry_type = 'DEPOSIT' AND invoice_id IS NULL)
    OR (entry_type = 'PREMIUM_PAID' AND invoice_id IS NOT NULL AND payment_id IS NULL)
    OR (entry_type = 'WITHDRAWAL' AND withdrawal_id IS NOT NULL AND invoice_id IS NULL AND payment_id IS NULL));
-- A withdrawal leaves the balance once.
CREATE UNIQUE INDEX IF NOT EXISTS ux_balance_entry_withdrawal
    ON billing.balance_entry (tenant_id, withdrawal_id) WHERE entry_type = 'WITHDRAWAL';

-- The balance now counts withdrawals out, for the drain's selection.
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
