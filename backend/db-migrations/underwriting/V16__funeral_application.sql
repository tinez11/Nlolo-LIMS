-- db-migrations/underwriting/V16__funeral_application.sql
-- Family funeral cover: the plan a funeral applicant picked and the dependants to cover. The main member
-- is NOT a row here: they are the case's life assured, a registered party. Read only after the product's
-- category says FUNERAL, so no other test class needs this migration. Changeable until the case is decided.
CREATE TABLE underwriting.funeral_application (
    case_id     UUID PRIMARY KEY REFERENCES underwriting.underwriting_case(case_id),
    tenant_id   UUID NOT NULL,
    plan_code   VARCHAR(20) NOT NULL,
    recorded_by VARCHAR(100) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE underwriting.funeral_application_life (
    funeral_application_life_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id       UUID NOT NULL REFERENCES underwriting.funeral_application(case_id) ON DELETE CASCADE,
    tenant_id     UUID NOT NULL,
    role          VARCHAR(20) NOT NULL CHECK (role IN ('SPOUSE','CHILD','PARENT','EXTENDED')),
    full_name     VARCHAR(200) NOT NULL,
    date_of_birth DATE NOT NULL,
    sex           VARCHAR(10) CHECK (sex IS NULL OR sex IN ('FEMALE','MALE')),
    id_number     VARCHAR(50),
    student       BOOLEAN NOT NULL DEFAULT false,
    -- The order the lives were listed in, so the quote and the policy list them the same way.
    position      INTEGER NOT NULL,
    UNIQUE (case_id, position)
);
CREATE INDEX idx_funeral_application_life_case ON underwriting.funeral_application_life (case_id, position);

ALTER TABLE underwriting.funeral_application ENABLE ROW LEVEL SECURITY;
CREATE POLICY funeral_application_tenant_isolation ON underwriting.funeral_application
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE underwriting.funeral_application_life ENABLE ROW LEVEL SECURITY;
CREATE POLICY funeral_application_life_tenant_isolation ON underwriting.funeral_application_life
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON underwriting.funeral_application, underwriting.funeral_application_life
    TO app_role;
