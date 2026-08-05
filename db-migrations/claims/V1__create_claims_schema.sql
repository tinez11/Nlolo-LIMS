-- Module: claims (Claims Management) -- Deliverable 3 Rev 2 §6
-- Owns: claim, claim_assessment, settlement_decision

CREATE SCHEMA IF NOT EXISTS claims;

CREATE TABLE claims.claim (
    claim_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL,        -- opaque ref into policy
    claimant_party_id   UUID NOT NULL,                -- opaque ref into party
    claim_type          VARCHAR(20) NOT NULL CHECK (claim_type IN ('DEATH','DISABILITY','CRITICAL_ILLNESS','MATURITY')),
    status              VARCHAR(25) NOT NULL DEFAULT 'REGISTERED' CHECK (status IN
        ('REGISTERED','UNDER_ASSESSMENT','APPROVED','REJECTED','SETTLEMENT_REQUESTED','SETTLED','REOPENED')),
    date_of_event       DATE NOT NULL,
    details             JSONB NOT NULL,   -- sealed ClaimDetails hierarchy (Deliverable 3 Rev 2, Cl2) -- concrete shape per claim_type
    approved_amount     NUMERIC(19,2),
    approved_currency   CHAR(3),
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100)
);
CREATE INDEX idx_claim_tenant ON claims.claim (tenant_id);
CREATE INDEX idx_claim_policy ON claims.claim (policy_number);        -- IC1
CREATE INDEX idx_claim_claimant ON claims.claim (claimant_party_id);  -- IC1
CREATE INDEX idx_claim_status ON claims.claim (tenant_id, status);

CREATE TABLE claims.claim_assessment (
    claim_assessment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    claim_id            UUID NOT NULL REFERENCES claims.claim(claim_id),
    assessor            VARCHAR(100) NOT NULL,
    findings            TEXT,
    recommended_amount  NUMERIC(19,2),
    recommended_currency CHAR(3),
    fraud_indicator     BOOLEAN NOT NULL DEFAULT false,   -- Cl1: scrutiny signal only, does not itself reject
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_claim_assessment_claim ON claims.claim_assessment (claim_id);
CREATE INDEX idx_claim_assessment_fraud ON claims.claim_assessment (tenant_id) WHERE fraud_indicator;

CREATE TABLE claims.settlement_decision (
    settlement_decision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL,
    claim_id               UUID NOT NULL REFERENCES claims.claim(claim_id),
    decided_by             VARCHAR(100) NOT NULL,   -- CLAIMS_MANAGER role, distinct from assessor (separation of duties)
    approved               BOOLEAN NOT NULL,
    approved_amount        NUMERIC(19,2),
    approved_currency      CHAR(3),
    rejection_reason       VARCHAR(500),
    decided_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_settlement_decision_claim ON claims.settlement_decision (claim_id);

-- Invariant enforced at application layer: APPROVED requires >=1 claim_assessment row,
-- EXCEPT claim_type = MATURITY which may auto-progress REGISTERED -> APPROVED with none
-- (Deliverable 3 Rev 2, Cl3 -- documented here again per that item's explicit request
-- that this not be mistaken for a missed validation).

ALTER TABLE claims.claim ENABLE ROW LEVEL SECURITY;
CREATE POLICY claim_tenant_isolation ON claims.claim
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
