-- db-migrations/underwriting/V21__case_account_charges.sql
-- The account charges chosen on a savings case (2026-10-09, product V32): carried onto the policy when it is issued,
-- automatically or by hand. None chosen means the product version's own charges. charge_id is an opaque reference
-- into product.

CREATE TABLE underwriting.case_account_charge (
    case_id    UUID NOT NULL REFERENCES underwriting.underwriting_case (case_id),
    charge_id  UUID NOT NULL,
    tenant_id  UUID NOT NULL,
    PRIMARY KEY (case_id, charge_id)
);

ALTER TABLE underwriting.case_account_charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY case_account_charge_tenant_isolation ON underwriting.case_account_charge
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, DELETE ON underwriting.case_account_charge TO app_role;
