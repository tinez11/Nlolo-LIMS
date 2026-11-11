-- Agent cash (2026-11-10/11). Two changes to billing.field_receipt.
--
-- 1. The agent says where the cash went (the user: agents collect cash and the company must see what each one is
-- still holding). An agent who has banked the cash, or sent it by mobile money, records the slip or transaction
-- reference against their collections. It is the agent's word, not money received: the cash stays open until finance
-- records the money and a second person confirms it. What it buys is a reference finance can match against the bank
-- statement, and an agent who no longer reads as sitting on cash they have already paid in.
--
-- 2. EXPIRED (2026-11-11, the user's decision): cash an agent records pays the customer's premium at once -- the
-- customer has paid, the debt is the agent's (Dr 1320 Due from agents). Cash the company never receives within the
-- agreed days is undone: the deposit is reversed, the premium is owed again and the receipt reads EXPIRED.
ALTER TABLE billing.field_receipt
    ADD COLUMN banked_at        TIMESTAMPTZ,
    ADD COLUMN banked_reference VARCHAR(100);

ALTER TABLE billing.field_receipt
    ADD CONSTRAINT field_receipt_banked_together
        CHECK ((banked_at IS NULL) = (banked_reference IS NULL));

-- The agent's own list: what they still hold, newest first.
CREATE INDEX idx_field_receipt_agent ON billing.field_receipt (tenant_id, agent_id, captured_at_server DESC);

ALTER TABLE billing.field_receipt DROP CONSTRAINT field_receipt_status_check;
ALTER TABLE billing.field_receipt
    ADD CONSTRAINT field_receipt_status_check
        CHECK (status IN ('PENDING_RECONCILIATION', 'RECONCILED', 'RECONCILIATION_OVERDUE', 'EXPIRED'));

-- Agent cash still not received after p_days, across every tenant. Cash that paid onto a policy's balance when it was
-- recorded is undone; cash that paid nothing yet (savings, an annuity, anything recorded before 2026-11-11) only
-- expires, so the dunning and offer expiry it was holding back resume. SECURITY DEFINER for the reason
-- balances_due_for_allocation is: a background thread has no tenant, so RLS would show it nothing.
CREATE OR REPLACE FUNCTION billing.agent_cash_past_expiry(p_days INTEGER)
RETURNS TABLE (tenant_id UUID, receipt_id UUID)
-- plpgsql, not sql: its body is checked when it runs, not when it is created, so test schemas that load this file
-- without the balance tables (V14) still load.
LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
    RETURN QUERY
    SELECT f.tenant_id, f.receipt_id
      FROM billing.field_receipt f
     WHERE f.status IN ('PENDING_RECONCILIATION', 'RECONCILIATION_OVERDUE')
       AND f.captured_at_server < now() - make_interval(days => p_days)
     LIMIT 500;
END;
$$;
REVOKE EXECUTE ON FUNCTION billing.agent_cash_past_expiry(INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing.agent_cash_past_expiry(INTEGER) TO app_role;
