-- Module: policy (Policy Administration) -- Deliverable 3 Rev 2 §3
-- Owns: policy, policy_account, endorsement, beneficiary, coverage, loan_value_reservation

CREATE SCHEMA IF NOT EXISTS policy;

CREATE TABLE policy.policy (
    policy_number       VARCHAR(20) PRIMARY KEY,     -- business key: tenant/product/year/sequence, human-meaningful for USSD/call-center lookup
    tenant_id           UUID NOT NULL,
    policyholder_party_id UUID NOT NULL,             -- opaque ref into party
    product_id          UUID NOT NULL,               -- opaque ref into product
    product_version_id  UUID NOT NULL,               -- opaque ref into product
    agent_of_record_id  UUID,                        -- opaque ref into distribution
    status              VARCHAR(20) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN
        ('PROPOSED','ACTIVE','LAPSED','SUSPENDED','SURRENDERED','MATURED','REINSTATED')),
    issue_date          DATE,
    sum_assured_amount  NUMERIC(19,2) NOT NULL,
    sum_assured_currency CHAR(3) NOT NULL DEFAULT 'TZS',
    version             BIGINT NOT NULL DEFAULT 0,    -- optimistic lock: genuinely concurrent multi-channel access
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100)
);
CREATE INDEX idx_policy_tenant ON policy.policy (tenant_id);
CREATE INDEX idx_policy_holder ON policy.policy (policyholder_party_id);        -- IC1
CREATE INDEX idx_policy_agent ON policy.policy (agent_of_record_id);            -- IC1
CREATE INDEX idx_policy_status ON policy.policy (tenant_id, status);

CREATE TABLE policy.policy_account (
    policy_number       VARCHAR(20) PRIMARY KEY REFERENCES policy.policy(policy_number),
    tenant_id           UUID NOT NULL,
    cash_value_amount   NUMERIC(19,2) NOT NULL DEFAULT 0,
    cash_value_currency CHAR(3) NOT NULL DEFAULT 'TZS',
    -- Non-authoritative projection (Deliverable 3 Rev 2 §3) -- updated only by consuming
    -- policyloan's LoanOriginated/LoanRepaid/LoanSettledForPayout events. policyloan
    -- remains system of record; this column exists purely so getAvailableLoanValue()
    -- doesn't need a synchronous cross-module read on every call.
    loan_encumbrance_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    version             BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE policy.fund_holding (
    fund_holding_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL REFERENCES policy.policy(policy_number),
    fund_id             UUID NOT NULL,   -- opaque ref into product.fund_definition
    units               NUMERIC(19,6) NOT NULL DEFAULT 0,
    book_value_amount   NUMERIC(19,2) NOT NULL DEFAULT 0,
    book_value_currency CHAR(3) NOT NULL DEFAULT 'TZS'
);
CREATE INDEX idx_fund_holding_policy ON policy.fund_holding (policy_number);

CREATE TABLE policy.endorsement (
    endorsement_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL REFERENCES policy.policy(policy_number),
    endorsement_type    VARCHAR(50) NOT NULL,
    effective_date      DATE NOT NULL,
    changes             JSONB NOT NULL,
    approved_by         VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
    -- Append-only: no UPDATE/DELETE grant for the application role.
);
CREATE INDEX idx_endorsement_policy ON policy.endorsement (policy_number, effective_date);

CREATE TABLE policy.beneficiary (
    beneficiary_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL REFERENCES policy.policy(policy_number),
    beneficiary_type     VARCHAR(10) NOT NULL CHECK (beneficiary_type IN ('PARTY','FREEFORM')),  -- Po2
    party_id            UUID,               -- required iff type = PARTY
    freeform_designee    VARCHAR(255),        -- required iff type = FREEFORM
    share_percent        NUMERIC(5,2) NOT NULL CHECK (share_percent >= 0 AND share_percent <= 100),
    revocable            BOOLEAN NOT NULL DEFAULT true,
    active               BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_beneficiary_exactly_one_designation CHECK (
        (beneficiary_type = 'PARTY' AND party_id IS NOT NULL AND freeform_designee IS NULL)
        OR (beneficiary_type = 'FREEFORM' AND freeform_designee IS NOT NULL AND party_id IS NULL)
    )
);
CREATE INDEX idx_beneficiary_policy ON policy.beneficiary (policy_number) WHERE active;
-- Application-layer invariant (not a single-row CHECK): sum(share_percent) over active
-- beneficiaries per policy_number must equal 100 -- enforced in the aggregate's
-- application service on every write, not a DB constraint (requires a cross-row
-- aggregate check, best done in a single transaction at the application layer, not
-- a trigger, so the 422 Problem Details message stays meaningful to the caller).

CREATE TABLE policy.coverage (
    coverage_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL REFERENCES policy.policy(policy_number),
    benefit_type        VARCHAR(20) NOT NULL CHECK (benefit_type IN ('DEATH','DISABILITY','CRITICAL_ILLNESS','MATURITY','SURRENDER')),
    sum_assured_amount  NUMERIC(19,2) NOT NULL,
    sum_assured_currency CHAR(3) NOT NULL DEFAULT 'TZS',
    active              BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_coverage_policy ON policy.coverage (policy_number) WHERE active;

CREATE TABLE policy.loan_value_reservation (
    reservation_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL REFERENCES policy.policy(policy_number),
    amount              NUMERIC(19,2) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'TZS',
    status              VARCHAR(15) NOT NULL DEFAULT 'RESERVED' CHECK (status IN ('RESERVED','CONFIRMED','RELEASED','EXPIRED')),
    ttl_expires_at      TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_loan_reservation_policy ON policy.loan_value_reservation (policy_number, status);
-- Background sweep job (Deliverable 3 Rev 2, B1 fix) queries WHERE status='RESERVED'
-- AND ttl_expires_at < now() to auto-release stale reservations -- this index serves that query too.
CREATE INDEX idx_loan_reservation_ttl_sweep ON policy.loan_value_reservation (ttl_expires_at) WHERE status = 'RESERVED';

-- Invariant enforced at application layer, not DB: cannot transition policy.status to
-- SURRENDERED/MATURED while any loan_value_reservation for that policy_number is RESERVED.

ALTER TABLE policy.policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_tenant_isolation ON policy.policy
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
