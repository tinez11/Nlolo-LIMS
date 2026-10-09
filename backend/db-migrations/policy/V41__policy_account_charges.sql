-- db-migrations/policy/V41__policy_account_charges.sql
-- The account charges a savings policy was issued on (2026-10-09, product V32), copied from its case or chosen on the
-- manual issue screen. Fixed for the policy's life; none means its product version's own charges. charge_id is an
-- opaque reference into product.

CREATE TABLE policy.policy_account_charge (
    policy_number  VARCHAR(20) NOT NULL REFERENCES policy.policy (policy_number),
    charge_id      UUID        NOT NULL,
    tenant_id      UUID        NOT NULL,
    PRIMARY KEY (policy_number, charge_id)
);

ALTER TABLE policy.policy_account_charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_account_charge_tenant_isolation ON policy.policy_account_charge
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- DELETE: the manual issue screen's choice replaces the case's, in the request that issues the policy.
GRANT SELECT, INSERT, DELETE ON policy.policy_account_charge TO app_role;
