-- Module: payment (Payments & Disbursements) -- Deliverable 3 Rev 2 §7.3
-- Owns: payment_transaction (partitioned), disbursement_instruction (partitioned), payout_batch
-- Per Deliverable 2 Rev 2: purely event-driven -- every row here originates from a
-- consumed *Requested event, never a direct external write.

CREATE SCHEMA IF NOT EXISTS payment;

CREATE TABLE payment.payment_transaction (
    payment_transaction_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL,
    idempotency_key         VARCHAR(100) NOT NULL,
    payer_ref               VARCHAR(255) NOT NULL,
    amount                  NUMERIC(19,2) NOT NULL,
    currency                CHAR(3) NOT NULL DEFAULT 'TZS',
    status                  VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','CONFIRMED','FAILED')),
    gateway_reference        VARCHAR(255),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_transaction_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE payment.payment_transaction_2026_08 PARTITION OF payment.payment_transaction
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE payment.payment_transaction_2026_09 PARTITION OF payment.payment_transaction
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

-- Postgres requires a unique constraint on a partitioned table to include the
-- partition key -- (idempotency_key, created_at) alone would NOT actually stop a
-- replay landing under a different created_at, which defeats the entire point of the
-- idempotency key. So true global uniqueness is enforced by a small, NON-partitioned
-- registry table instead (below), checked/inserted in the same transaction as the
-- ledger row. This was caught by executing this migration against a live PostgreSQL
-- 16 instance, not just parsing it -- the original single-column unique index does
-- not work on a partitioned table at all.
CREATE INDEX idx_payment_transaction_idempotency ON payment.payment_transaction (idempotency_key);
CREATE INDEX idx_payment_transaction_tenant ON payment.payment_transaction (tenant_id);
REVOKE UPDATE, DELETE ON payment.payment_transaction FROM app_role;

CREATE TABLE payment.payment_transaction_idempotency_registry (
    idempotency_key         VARCHAR(100) PRIMARY KEY,
    payment_transaction_id  UUID NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Application inserts here first (or via ON CONFLICT DO NOTHING) before writing the
-- ledger row -- a conflict here means "already processed," and the event is dropped
-- as a safe duplicate rather than double-posting to payment_transaction.

CREATE TABLE payment.disbursement_instruction (
    disbursement_id     UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    idempotency_key      VARCHAR(100) NOT NULL,
    payee_ref            VARCHAR(255) NOT NULL,
    amount               NUMERIC(19,2) NOT NULL,
    currency             CHAR(3) NOT NULL DEFAULT 'TZS',
    purpose              VARCHAR(30) NOT NULL CHECK (purpose IN
        ('LOAN_DISBURSEMENT','CLAIM_SETTLEMENT','COMMISSION_PAYOUT','SURRENDER_PAYOUT','MATURITY_PAYOUT')),
    status               VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    gateway_reference     VARCHAR(255),
    batch_id              UUID,   -- nullable; set when part of a payout_batch
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (disbursement_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE payment.disbursement_instruction_2026_08 PARTITION OF payment.disbursement_instruction
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE payment.disbursement_instruction_2026_09 PARTITION OF payment.disbursement_instruction
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE INDEX idx_disbursement_idempotency ON payment.disbursement_instruction (idempotency_key);
CREATE INDEX idx_disbursement_tenant ON payment.disbursement_instruction (tenant_id);
CREATE INDEX idx_disbursement_batch ON payment.disbursement_instruction (batch_id) WHERE batch_id IS NOT NULL;
REVOKE UPDATE, DELETE ON payment.disbursement_instruction FROM app_role;

CREATE TABLE payment.disbursement_idempotency_registry (
    idempotency_key       VARCHAR(100) PRIMARY KEY,
    disbursement_id       UUID NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Same registry pattern as payment_transaction_idempotency_registry above.

-- PayoutBatch (Deliverable 3 Rev 2, Pay1) -- references disbursement_instruction by ID
-- only, batch-level status derived from referenced instructions' statuses, not duplicated.
CREATE TABLE payment.payout_batch (
    batch_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    batch_type           VARCHAR(20) NOT NULL CHECK (batch_type IN ('COMMISSION_RUN','MATURITY_BATCH','DIVIDEND_RUN')),
    status               VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','COMPLETED','PARTIAL_FAILURE')),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payout_batch_tenant ON payment.payout_batch (tenant_id);

-- Note: RLS is intentionally omitted here beyond tenant_id indexing -- `payment` has
-- no synchronous external callers at all (Deliverable 2 Rev 2); every row originates
-- from an internal event listener already running in the correct tenant context, so
-- there's no untrusted caller path RLS would be defending against. Still indexed on
-- tenant_id for query performance and for the reporting/audit read paths.
