-- Module: regreporting (Regulatory Reporting / TIRA) -- Deliverable 3 §10 (CQRS read-model only)
-- No independent business aggregate -- rebuilt from events. Denormalized read tables only.

CREATE SCHEMA IF NOT EXISTS regreporting;

CREATE TABLE regreporting.regulatory_return (
    return_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    return_type           VARCHAR(30) NOT NULL,   -- PLACEHOLDER catalog pending TIRA circular (C2): QUARTERLY_PRUDENTIAL, ANNUAL_AUDITED, STATISTICAL
    period                 VARCHAR(10) NOT NULL,   -- 'YYYY-Qn' or 'YYYY'
    status                  VARCHAR(15) NOT NULL DEFAULT 'GENERATING' CHECK (status IN ('GENERATING','READY')),
    document_ref              VARCHAR(255),          -- opaque ref into document, once READY
    generated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_regulatory_return_tenant ON regreporting.regulatory_return (tenant_id, period);

-- Denormalized read-model tables, one per reporting dimension, rebuilt via event
-- projection (event-carried state transfer) rather than owning transactional state.
-- Only a representative one is shown -- the full set (policy-in-force summary,
-- claims summary, premium summary, solvency inputs, etc.) is finalized once the
-- TIRA return catalog (C2) is confirmed, to avoid building projections for a return
-- format that may not match the real one.
CREATE TABLE regreporting.policy_in_force_summary (
    summary_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    period                   VARCHAR(10) NOT NULL,
    product_id                UUID NOT NULL,
    policy_count                INTEGER NOT NULL DEFAULT 0,
    total_sum_assured_amount      NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_sum_assured_currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    computed_at                        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_policy_in_force_summary ON regreporting.policy_in_force_summary (tenant_id, period, product_id);
