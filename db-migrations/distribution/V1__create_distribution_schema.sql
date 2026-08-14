-- Module: distribution (Distribution & Channel Management) -- Deliverable 3 Rev 2 §7.2
-- Owns: agent_profile (hierarchy via self-FK hierarchy_parent_id), commission_plan, commission_rule, commission_statement

CREATE SCHEMA IF NOT EXISTS distribution;

CREATE TABLE distribution.agent_profile (
    agent_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    party_id              UUID NOT NULL,    -- opaque ref into party -- onboarding requires KYC VERIFIED, checked at application layer
    license_number        VARCHAR(50) NOT NULL,
    license_status         VARCHAR(15) NOT NULL DEFAULT 'ACTIVE' CHECK (license_status IN ('ACTIVE','EXPIRED','SUSPENDED')),
    license_expiry_date     DATE NOT NULL,
    hierarchy_parent_id      UUID REFERENCES distribution.agent_profile(agent_id),
    commission_plan_id       UUID,   -- FK added once commission_plan exists below
    version                  BIGINT NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                VARCHAR(100),
    updated_at                 TIMESTAMPTZ,
    updated_by                 VARCHAR(100)
);
CREATE UNIQUE INDEX ux_agent_license ON distribution.agent_profile (tenant_id, license_number);
CREATE INDEX idx_agent_party ON distribution.agent_profile (party_id);
CREATE INDEX idx_agent_hierarchy_parent ON distribution.agent_profile (hierarchy_parent_id);
CREATE INDEX idx_agent_tenant ON distribution.agent_profile (tenant_id);
CREATE INDEX idx_agent_license_expiry ON distribution.agent_profile (license_expiry_date) WHERE license_status = 'ACTIVE';

-- CommissionPlan promoted to its own aggregate root (Deliverable 3 Rev 2, Di1) --
-- supports first-year/renewal/override/supervisor-override/threshold tiers, which a
-- flat percentage on agent_profile could not.
CREATE TABLE distribution.commission_plan (
    commission_plan_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    product_id            UUID NOT NULL,   -- opaque ref into product
    status                VARCHAR(15) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RETIRED')),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_commission_plan_product ON distribution.commission_plan (product_id, status);
CREATE INDEX idx_commission_plan_tenant ON distribution.commission_plan (tenant_id);

ALTER TABLE distribution.agent_profile
    ADD CONSTRAINT fk_agent_commission_plan FOREIGN KEY (commission_plan_id)
    REFERENCES distribution.commission_plan(commission_plan_id);

CREATE TABLE distribution.commission_rule (
    commission_rule_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL,
    commission_plan_id      UUID NOT NULL REFERENCES distribution.commission_plan(commission_plan_id),
    tier_type                VARCHAR(20) NOT NULL CHECK (tier_type IN
        ('FIRST_YEAR','RENEWAL','OVERRIDE','SUPERVISOR_OVERRIDE','THRESHOLD_BONUS')),
    rate                     NUMERIC(7,4),
    flat_amount               NUMERIC(19,2),
    flat_currency              CHAR(3),
    threshold_condition          JSONB
);
CREATE INDEX idx_commission_rule_plan ON distribution.commission_rule (commission_plan_id);

CREATE TABLE distribution.commission_statement (
    statement_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID NOT NULL,
    agent_id                  UUID NOT NULL REFERENCES distribution.agent_profile(agent_id),
    period                     VARCHAR(7) NOT NULL,   -- 'YYYY-MM'
    total_amount                NUMERIC(19,2) NOT NULL DEFAULT 0,
    total_currency                CHAR(3) NOT NULL DEFAULT 'TZS',
    status                        VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PAID')),
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_commission_statement_agent ON distribution.commission_statement (agent_id, period);
CREATE INDEX idx_commission_statement_tenant ON distribution.commission_statement (tenant_id);

ALTER TABLE distribution.agent_profile ENABLE ROW LEVEL SECURITY;
CREATE POLICY agent_profile_tenant_isolation ON distribution.agent_profile
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
