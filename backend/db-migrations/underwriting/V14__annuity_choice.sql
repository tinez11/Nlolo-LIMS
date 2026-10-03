-- db-migrations/underwriting/V14__annuity_choice.sql
-- Product step 5 (D1): what an annuity applicant chose, and the light path's one piece of evidence.
--
-- Its own table, keyed by case and read only for an ANNUITY product (the product is asked first), so
-- no other case reads it and no existing test class needs this migration. Age evidence lives HERE
-- rather than on underwriting_case (plan R8): a mapped column on the case would have forced V14 into
-- every class that touches underwriting.
CREATE TABLE underwriting.annuity_choice (
    case_id                    UUID PRIMARY KEY REFERENCES underwriting.underwriting_case(case_id),
    tenant_id                  UUID NOT NULL,
    form_code                  VARCHAR(30) NOT NULL,
    frequency                  VARCHAR(12) NOT NULL CHECK (frequency IN ('MONTHLY','QUARTERLY','SEMI_ANNUAL','ANNUAL')),
    joint_life_party_id        UUID,
    recorded_by                VARCHAR(100) NOT NULL,
    recorded_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Set by the deciding underwriter on ACCEPT: proof of age was seen, since age picks the rate.
    age_evidence_confirmed_by  VARCHAR(100),
    age_evidence_confirmed_at  TIMESTAMPTZ,
    CHECK ((age_evidence_confirmed_by IS NULL) = (age_evidence_confirmed_at IS NULL))
);

ALTER TABLE underwriting.annuity_choice ENABLE ROW LEVEL SECURITY;
CREATE POLICY annuity_choice_tenant_isolation ON underwriting.annuity_choice
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON underwriting.annuity_choice TO app_role;
