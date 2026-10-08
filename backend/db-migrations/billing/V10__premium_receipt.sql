-- Payment schedules (2026-10-07): every payment applied to an invoice, with when it arrived, the rail's
-- reference and who paid. premium_invoice keeps only a running amount_paid, so a customer's schedule could
-- say an invoice was paid but never when, by whom, or under which receipt reference.
--
-- Written by BillingApiImpl.applyConfirmedPayment -- the one place a collection reaches an invoice. A payment
-- confirmation can be redelivered, so a reference is recorded once per invoice.
CREATE TABLE billing.premium_receipt (
    receipt_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    policy_number     VARCHAR(20) NOT NULL,
    invoice_id        UUID NOT NULL,
    amount            NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency          CHAR(3) NOT NULL,
    received_at       TIMESTAMPTZ NOT NULL,
    payment_reference VARCHAR(100),
    payer_ref         VARCHAR(100),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_premium_receipt_reference ON billing.premium_receipt (tenant_id, invoice_id, payment_reference)
    WHERE payment_reference IS NOT NULL;
CREATE INDEX idx_premium_receipt_policy ON billing.premium_receipt (tenant_id, policy_number, received_at);

ALTER TABLE billing.premium_receipt ENABLE ROW LEVEL SECURITY;
CREATE POLICY premium_receipt_tenant_isolation ON billing.premium_receipt
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON billing.premium_receipt TO app_role;

-- The payments already confirmed, from the payment module's own record, where that schema is present (a module
-- test may apply billing without it).
DO $$
BEGIN
    IF to_regclass('payment.payment_transaction') IS NULL THEN
        RETURN;
    END IF;
    INSERT INTO billing.premium_receipt (tenant_id, policy_number, invoice_id, amount, currency, received_at,
                                         payment_reference, payer_ref)
    SELECT t.tenant_id, i.policy_number, i.invoice_id, t.amount, t.currency, t.created_at,
           COALESCE(t.gateway_reference, t.payment_transaction_id::text), t.payer_ref
      FROM payment.payment_transaction t
      JOIN billing.premium_invoice i ON i.invoice_id::text = t.source_ref AND i.tenant_id = t.tenant_id
     WHERE t.status = 'CONFIRMED' AND t.purpose = 'PREMIUM' AND t.amount > 0
    ON CONFLICT DO NOTHING;
END $$;
