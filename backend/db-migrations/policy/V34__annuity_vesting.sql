-- db-migrations/policy/V34__annuity_vesting.sql
-- Product step 5 (D2): a deferred annuity's vesting, on the policy's own record. Policy cannot see
-- accumulation or annuity, and from this date the policy is an annuity in payment, not an account:
-- no surrender, no paid-up, and a settled death claim does not close it. Its own table, read only
-- after product says the version is deferred, so no policy test class needs it.
CREATE TABLE policy.annuity_vesting (
    policy_number  VARCHAR(20) PRIMARY KEY REFERENCES policy.policy(policy_number),
    tenant_id      UUID NOT NULL,
    vested_on      DATE NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE policy.annuity_vesting ENABLE ROW LEVEL SECURITY;
CREATE POLICY annuity_vesting_tenant_isolation ON policy.annuity_vesting
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON policy.annuity_vesting TO app_role;
