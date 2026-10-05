-- db-migrations/underwriting/V17__unit_linked_choice.sql
-- Product step 6 (U1): what a unit-linked case is being sold -- the customer's fund split, their chosen premium
-- and frequency, and their sum assured, each checked against the version's terms when recorded. Replaceable
-- until the case is decided. Absent on every other case, and read only once the product says UNIT_LINKED.
CREATE TABLE underwriting.unit_linked_choice (
    case_id           UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    premium_amount    NUMERIC(19,2) NOT NULL CHECK (premium_amount > 0),
    premium_frequency VARCHAR(12) NOT NULL
        CHECK (premium_frequency IN ('MONTHLY','QUARTERLY','ANNUALLY','SINGLE')),
    sum_assured       NUMERIC(19,2) NOT NULL CHECK (sum_assured > 0),
    recorded_by       VARCHAR(100) NOT NULL,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE underwriting.unit_linked_choice_split (
    unit_linked_choice_split_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id                     UUID NOT NULL REFERENCES underwriting.unit_linked_choice(case_id),
    tenant_id                   UUID NOT NULL,
    fund_code                   VARCHAR(20) NOT NULL,
    percent                     INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 100),
    UNIQUE (case_id, fund_code)
);

ALTER TABLE underwriting.unit_linked_choice ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_choice_tenant_isolation ON underwriting.unit_linked_choice
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE underwriting.unit_linked_choice_split ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_linked_choice_split_tenant_isolation ON underwriting.unit_linked_choice_split
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- Re-recording a choice replaces its split, so the split rows are deleted as well as inserted.
GRANT SELECT, INSERT, UPDATE ON underwriting.unit_linked_choice TO app_role;
GRANT SELECT, INSERT, DELETE ON underwriting.unit_linked_choice_split TO app_role;
