-- Module: billing (Premium Billing & Collection) -- Deliverable 3 Rev 2 §5
-- Owns: billing_schedule, premium_invoice (partitioned), arrears_case, field_receipt

CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE billing.billing_schedule (
    billing_schedule_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL,          -- opaque ref into policy
    premium_frequency   VARCHAR(10) NOT NULL CHECK (premium_frequency IN ('MONTHLY','QUARTERLY','ANNUALLY')),
    premium_amount      NUMERIC(19,2) NOT NULL,
    premium_currency    CHAR(3) NOT NULL DEFAULT 'TZS',
    next_due_date       DATE,
    status              VARCHAR(15) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','TERMINATED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
    -- Never mutated in place on a premium change -- old schedule TERMINATED, new one
    -- created ACTIVE (Deliverable 3), so history is a sequence of rows, not edits.
);
CREATE INDEX idx_billing_schedule_policy ON billing.billing_schedule (policy_number, status);
CREATE INDEX idx_billing_schedule_tenant ON billing.billing_schedule (tenant_id);

-- Second-highest-volume table in this module after loan_transaction platform-wide --
-- one row per policy per billing period, every period, for the life of every policy.
-- Partitioned by due_date, yearly (lower write velocity than a transaction ledger, so
-- yearly partitions are appropriately coarser than loan_transaction's monthly ones).
CREATE TABLE billing.premium_invoice (
    invoice_id          UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    billing_schedule_id UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL,
    due_date            DATE NOT NULL,
    amount              NUMERIC(19,2) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'TZS',
    status              VARCHAR(15) NOT NULL DEFAULT 'DUE' CHECK (status IN ('DUE','PARTIALLY_PAID','PAID','IN_GRACE','OVERDUE','WAIVED')),
    grace_period_ends_at DATE,
    waiver_reason       VARCHAR(500),
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (invoice_id, due_date)
) PARTITION BY RANGE (due_date);

CREATE TABLE billing.premium_invoice_2026 PARTITION OF billing.premium_invoice
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE billing.premium_invoice_2027 PARTITION OF billing.premium_invoice
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
-- Subsequent yearly partitions created ahead of need -- same operational convention as loan_transaction.

CREATE INDEX idx_premium_invoice_policy ON billing.premium_invoice (policy_number, status);
CREATE INDEX idx_premium_invoice_tenant ON billing.premium_invoice (tenant_id);
CREATE INDEX idx_premium_invoice_next_due ON billing.premium_invoice (policy_number, due_date) WHERE status IN ('DUE','IN_GRACE');  -- serves Bi2's getNextDueInvoice

CREATE TABLE billing.arrears_case (
    arrears_case_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    invoice_id          UUID NOT NULL,     -- references premium_invoice (composite PK -- not a DB FK across the partition, tracked at application layer)
    policy_number       VARCHAR(20) NOT NULL,
    dunning_level       INTEGER NOT NULL DEFAULT 1 CHECK (dunning_level BETWEEN 1 AND 5),  -- Bi1
    opened_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ
);
CREATE INDEX idx_arrears_case_policy ON billing.arrears_case (policy_number);
CREATE INDEX idx_arrears_case_tenant ON billing.arrears_case (tenant_id);

-- BillingSyncApi offline capture (Deliverable 2 Rev 2, B4)
CREATE TABLE billing.field_receipt (
    receipt_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL,
    agent_id            UUID NOT NULL,       -- opaque ref into distribution
    amount              NUMERIC(19,2) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'TZS',
    client_idempotency_key VARCHAR(100) NOT NULL,
    captured_at_client  TIMESTAMPTZ NOT NULL,
    captured_at_server  TIMESTAMPTZ NOT NULL DEFAULT now(),
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING_RECONCILIATION' CHECK (status IN
        ('PENDING_RECONCILIATION','RECONCILED','RECONCILIATION_OVERDUE')),
    reconciled_at       TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_field_receipt_idempotency ON billing.field_receipt (client_idempotency_key);
CREATE INDEX idx_field_receipt_sla_sweep ON billing.field_receipt (captured_at_server) WHERE status = 'PENDING_RECONCILIATION';

ALTER TABLE billing.billing_schedule ENABLE ROW LEVEL SECURITY;
CREATE POLICY billing_schedule_tenant_isolation ON billing.billing_schedule
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
