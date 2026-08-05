-- Module: finaccounting (Financial Accounting / IFRS17) -- Deliverable 3 Rev 2 §8
-- PROVISIONAL / PLACEHOLDER -- still gated on C1 (actuarial cohort/grouping rules).
-- Structure below is a skeleton sufficient to receive events; do NOT treat column
-- shapes as final, and do not build real postings against this until C1 resolves.

CREATE SCHEMA IF NOT EXISTS finaccounting;

CREATE TABLE finaccounting.group_of_contracts (
    group_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    cohort_year            INTEGER NOT NULL,      -- PROVISIONAL: annual vs quarterly cohort boundary pending C1
    product_id              UUID NOT NULL,          -- opaque ref into product
    measurement_model         VARCHAR(10) NOT NULL CHECK (measurement_model IN ('GMM','PAA')),
    status                     VARCHAR(15) NOT NULL DEFAULT 'OPEN',
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_group_tenant ON finaccounting.group_of_contracts (tenant_id);

CREATE TABLE finaccounting.csm_ledger (
    csm_ledger_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    group_id                 UUID NOT NULL REFERENCES finaccounting.group_of_contracts(group_id),
    period                     VARCHAR(7) NOT NULL,
    csm_balance_amount           NUMERIC(19,2) NOT NULL,
    csm_balance_currency           CHAR(3) NOT NULL DEFAULT 'TZS',
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_csm_ledger_group ON finaccounting.csm_ledger (group_id, period);

CREATE TABLE finaccounting.lrc_ledger (
    lrc_ledger_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL,
    group_id                  UUID NOT NULL REFERENCES finaccounting.group_of_contracts(group_id),
    period                      VARCHAR(7) NOT NULL,
    balance_amount                NUMERIC(19,2) NOT NULL,
    balance_currency                CHAR(3) NOT NULL DEFAULT 'TZS'
);
CREATE INDEX idx_lrc_ledger_group ON finaccounting.lrc_ledger (group_id, period);

CREATE TABLE finaccounting.lic_ledger (
    lic_ledger_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID NOT NULL,
    group_id                   UUID NOT NULL REFERENCES finaccounting.group_of_contracts(group_id),
    period                       VARCHAR(7) NOT NULL,
    balance_amount                 NUMERIC(19,2) NOT NULL,
    balance_currency                 CHAR(3) NOT NULL DEFAULT 'TZS'
);
CREATE INDEX idx_lic_ledger_group ON finaccounting.lic_ledger (group_id, period);

-- Append-only, partitioned like the other ledgers -- volume here will be significant
-- once live (one posting per relevant business event per group).
CREATE TABLE finaccounting.gl_posting (
    posting_id              UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id                 UUID NOT NULL,
    group_id                   UUID NOT NULL,
    period                       VARCHAR(7) NOT NULL,
    amount                        NUMERIC(19,2) NOT NULL,
    currency                       CHAR(3) NOT NULL DEFAULT 'TZS',
    posting_type                     VARCHAR(30) NOT NULL,
    created_at                         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (posting_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE finaccounting.gl_posting_2026_08 PARTITION OF finaccounting.gl_posting
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE finaccounting.gl_posting_2026_09 PARTITION OF finaccounting.gl_posting
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
REVOKE UPDATE, DELETE ON finaccounting.gl_posting FROM app_role;
CREATE INDEX idx_gl_posting_group ON finaccounting.gl_posting (group_id, period);
