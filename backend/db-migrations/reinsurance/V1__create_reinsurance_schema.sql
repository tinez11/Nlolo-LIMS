-- Module: reinsurance (Reinsurance & Cessions) -- Deliverable 3 Rev 2 §7.4 (unchanged from Rev 1)
-- Owns: reinsurance_treaty, cession, claim_recovery

CREATE SCHEMA IF NOT EXISTS reinsurance;

CREATE TABLE reinsurance.reinsurance_treaty (
    treaty_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    treaty_type           VARCHAR(15) NOT NULL CHECK (treaty_type IN ('QUOTA_SHARE','SURPLUS','XOL')),
    retention_limit_amount NUMERIC(19,2) NOT NULL,
    retention_limit_currency CHAR(3) NOT NULL DEFAULT 'TZS',
    cession_percent        NUMERIC(5,2),
    effective_from          DATE NOT NULL,
    effective_to             DATE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_treaty_tenant ON reinsurance.reinsurance_treaty (tenant_id);
CREATE INDEX idx_treaty_effective ON reinsurance.reinsurance_treaty (effective_from, effective_to);

CREATE TABLE reinsurance.cession (
    cession_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL,
    policy_number           VARCHAR(20) NOT NULL,   -- opaque ref into policy
    treaty_id                UUID NOT NULL REFERENCES reinsurance.reinsurance_treaty(treaty_id),
    ceded_amount              NUMERIC(19,2) NOT NULL,
    ceded_currency              CHAR(3) NOT NULL DEFAULT 'TZS',
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cession_policy ON reinsurance.cession (policy_number);
CREATE INDEX idx_cession_treaty ON reinsurance.cession (treaty_id);
-- Invariant enforced at application layer: cumulative cessions for a policy across
-- all treaties must not exceed 100% of sum assured beyond the retention limit.

CREATE TABLE reinsurance.claim_recovery (
    recovery_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL,
    claim_id                  UUID NOT NULL,   -- opaque ref into claims
    treaty_id                  UUID NOT NULL REFERENCES reinsurance.reinsurance_treaty(treaty_id),
    recoverable_amount           NUMERIC(19,2) NOT NULL,
    recoverable_currency           CHAR(3) NOT NULL DEFAULT 'TZS',
    confirmed_at                     TIMESTAMPTZ,
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_claim_recovery_claim ON reinsurance.claim_recovery (claim_id);
CREATE INDEX idx_claim_recovery_treaty ON reinsurance.claim_recovery (treaty_id);

ALTER TABLE reinsurance.reinsurance_treaty ENABLE ROW LEVEL SECURITY;
CREATE POLICY reinsurance_treaty_tenant_isolation ON reinsurance.reinsurance_treaty
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
