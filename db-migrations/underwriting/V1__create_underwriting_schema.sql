-- Module: underwriting (Underwriting & Risk Assessment) -- Deliverable 3 Rev 2 §2
-- Owns: underwriting_case, risk_assessment, medical_disclosure

CREATE SCHEMA IF NOT EXISTS underwriting;

CREATE TABLE underwriting.underwriting_case (
    case_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    applicant_party_id  UUID NOT NULL,               -- opaque ref into party -- no cross-schema FK
    product_id          UUID NOT NULL,               -- opaque ref into product
    product_version_id  UUID NOT NULL,               -- opaque ref into product -- locked at case-open
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_REVIEW','DECIDED')),
    referral_status     VARCHAR(25) NOT NULL DEFAULT 'NONE' CHECK (referral_status IN ('NONE','REFERRED_TO_SENIOR','REFERRAL_RESOLVED')), -- U1
    -- decision embedded (1:1, never queried independently)
    decision_outcome    VARCHAR(10) CHECK (decision_outcome IN ('ACCEPT','LOADED','DECLINED','POSTPONED')),
    decision_loading_percent NUMERIC(5,2),
    decision_decline_reason  VARCHAR(500),
    decision_decided_at TIMESTAMPTZ,
    sum_assured_amount  NUMERIC(19,2),
    sum_assured_currency CHAR(3),
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    CONSTRAINT chk_loading_only_when_loaded CHECK (
        (decision_outcome = 'LOADED' AND decision_loading_percent IS NOT NULL)
        OR (decision_outcome IS DISTINCT FROM 'LOADED' AND decision_loading_percent IS NULL)
    )
);
CREATE INDEX idx_underwriting_case_tenant ON underwriting.underwriting_case (tenant_id);
CREATE INDEX idx_underwriting_case_applicant ON underwriting.underwriting_case (applicant_party_id);
CREATE INDEX idx_underwriting_case_status ON underwriting.underwriting_case (tenant_id, status);

CREATE TABLE underwriting.risk_assessment (
    risk_assessment_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    case_id             UUID NOT NULL REFERENCES underwriting.underwriting_case(case_id),
    assessment_type     VARCHAR(20) NOT NULL CHECK (assessment_type IN ('MEDICAL','FINANCIAL','OCCUPATIONAL')),
    assessor            VARCHAR(100) NOT NULL,
    findings            TEXT,
    risk_score          NUMERIC(9,4),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_assessment_case ON underwriting.risk_assessment (case_id);

CREATE TABLE underwriting.medical_disclosure (
    medical_disclosure_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    case_id               UUID NOT NULL REFERENCES underwriting.underwriting_case(case_id),
    question_response_set JSONB NOT NULL,   -- structured Q&A, product-specific field set
    document_refs         TEXT[],            -- opaque refs into document module
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_medical_disclosure_case ON underwriting.medical_disclosure (case_id);

ALTER TABLE underwriting.underwriting_case ENABLE ROW LEVEL SECURITY;
CREATE POLICY underwriting_case_tenant_isolation ON underwriting.underwriting_case
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
