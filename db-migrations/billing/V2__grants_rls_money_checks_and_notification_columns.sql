-- 1. RLS completion. V1 enabled RLS on billing_schedule only (1 of 4 tables) and had zero
-- GRANT statements anywhere in the file -- the exact bug class M1/M2/M3 each found and fixed
-- for every other schema. premium_invoice is PARTITION BY RANGE (due_date); Postgres does NOT
-- cascade ENABLE ROW LEVEL SECURITY from a partitioned parent to its partitions (empirically
-- established in M3 Task 1) -- both existing partitions need it applied explicitly.
ALTER TABLE billing.premium_invoice ENABLE ROW LEVEL SECURITY;
CREATE POLICY premium_invoice_tenant_isolation ON billing.premium_invoice
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE billing.premium_invoice_2026 ENABLE ROW LEVEL SECURITY;
CREATE POLICY premium_invoice_2026_tenant_isolation ON billing.premium_invoice_2026
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE billing.premium_invoice_2027 ENABLE ROW LEVEL SECURITY;
CREATE POLICY premium_invoice_2027_tenant_isolation ON billing.premium_invoice_2027
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE billing.arrears_case ENABLE ROW LEVEL SECURITY;
CREATE POLICY arrears_case_tenant_isolation ON billing.arrears_case
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE billing.field_receipt ENABLE ROW LEVEL SECURITY;
CREATE POLICY field_receipt_tenant_isolation ON billing.field_receipt
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- 2. Money CHECK constraints -- following db-migrations/policy/V2 and db-migrations/policyloan/V3's
-- precedent (a negative/zero amount was a real, fixed Critical in M3's policyloan.MoneyDto).
ALTER TABLE billing.billing_schedule ADD CONSTRAINT chk_billing_schedule_premium_positive CHECK (premium_amount > 0);
ALTER TABLE billing.premium_invoice ADD CONSTRAINT chk_premium_invoice_amount_positive CHECK (amount > 0);
ALTER TABLE billing.field_receipt ADD CONSTRAINT chk_field_receipt_amount_positive CHECK (amount > 0);

-- 3. Tenant-scoped idempotency. V1's ux_field_receipt_idempotency has no tenant_id -- two
-- different tenants' agents could collide on the same client-generated key.
DROP INDEX billing.ux_field_receipt_idempotency;
CREATE UNIQUE INDEX ux_field_receipt_idempotency ON billing.field_receipt (tenant_id, client_idempotency_key);

-- 4. Notification-tracking columns for the opportunistic per-tenant sweep (Task 5). These are
-- distinct from the business-state columns (dunning_level, status) that billing.sweep_billing_
-- state() (a pg_cron-driven, RLS-bypassing SECURITY DEFINER function -- see Task 5) maintains
-- on a real timer; these track whether the APPLICATION has published a domain event for the
-- current state yet, so a self-healing per-tenant sweep on next real access can catch up
-- without double-publishing. last_notified_dunning_level starts at 0 so level 1 always
-- triggers a first notification.
ALTER TABLE billing.arrears_case ADD COLUMN last_notified_dunning_level INTEGER NOT NULL DEFAULT 0;
ALTER TABLE billing.field_receipt ADD COLUMN notified_overdue_at TIMESTAMPTZ;

-- 5. Global (deliberately NOT tenant-scoped -- same class of decision as refdata.reference_code_set)
-- metrics snapshot the Micrometer gauge for the FieldReceiptReconciliationOverdue alert reads.
-- Written only by billing.sweep_billing_state() (SECURITY DEFINER); app_role gets SELECT only.
CREATE TABLE billing.overdue_metrics_snapshot (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    field_receipt_overdue_count INTEGER NOT NULL DEFAULT 0,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO billing.overdue_metrics_snapshot (id) VALUES (1);

-- 6. Standard grant block, established in party/product/underwriting/refdata/policy/policyloan.
GRANT USAGE ON SCHEMA billing TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA billing TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA billing GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- 7. app_role may read the metrics snapshot but never write it -- only the SECURITY DEFINER
-- function (running as the migration/table-owner role) does. REVOKE placed AFTER the blanket
-- GRANT above, per the established ordering rule (a REVOKE before a later broader GRANT is
-- silently undone) -- last statement in this file, mirroring policyloan.loan_transaction's
-- append-only REVOKE placement.
REVOKE INSERT, UPDATE, DELETE ON billing.overdue_metrics_snapshot FROM app_role;
