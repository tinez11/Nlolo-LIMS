-- db-migrations/underwriting/V15__deferred_annuity_choice.sql
-- Product step 5 (D2): what a deferred annuity applicant chose -- a retirement age, which gives the
-- target vesting date -- and, at acceptance, the date of birth and sex whose proof was seen (spec
-- Q8: vesting re-confirms only if either has changed since). Its own table, as D1's annuity_choice,
-- read only for a deferred ANNUITY version (the product is asked first).
CREATE TABLE underwriting.deferred_annuity_choice (
    case_id                    UUID PRIMARY KEY REFERENCES underwriting.underwriting_case(case_id),
    tenant_id                  UUID NOT NULL,
    retirement_age             INTEGER NOT NULL CHECK (retirement_age BETWEEN 0 AND 120),
    recorded_by                VARCHAR(100) NOT NULL,
    recorded_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    age_evidence_confirmed_by  VARCHAR(100),
    age_evidence_confirmed_at  TIMESTAMPTZ,
    confirmed_date_of_birth    DATE,
    -- party.api.Sex's two literals; null when the party record has no sex (a UNISEX default form
    -- prices without it, and vesting compares null to null).
    confirmed_sex              VARCHAR(10) CHECK (confirmed_sex IN ('FEMALE','MALE')),
    CHECK ((age_evidence_confirmed_by IS NULL) = (age_evidence_confirmed_at IS NULL)),
    CHECK (age_evidence_confirmed_by IS NULL OR confirmed_date_of_birth IS NOT NULL)
);

ALTER TABLE underwriting.deferred_annuity_choice ENABLE ROW LEVEL SECURITY;
CREATE POLICY deferred_annuity_choice_tenant_isolation ON underwriting.deferred_annuity_choice
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON underwriting.deferred_annuity_choice TO app_role;
