-- db-migrations/policy/V32__attached_bonus_projection.sql
-- Product step 4: the attached-bonus total, a PROJECTION the bonus module restates after every
-- entry -- so the surrender quote can read it without policy depending on bonus (a cycle).
--
-- A separate table, not a column on policy_account: that entity is read by nearly every test class
-- that issues a policy, and ddl-auto none would break all of them on a column they lack. A row
-- exists only once a bonus has attached; its absence is zero. Readers reach it only after product
-- says the version is with-profits (plan §12, L6), so no other test class needs this migration.
CREATE TABLE policy.policy_bonus (
    policy_number          VARCHAR(20) PRIMARY KEY,
    tenant_id              UUID NOT NULL,
    attached_bonus_amount  NUMERIC(19,2) NOT NULL CHECK (attached_bonus_amount >= 0),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE policy.policy_bonus ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_bonus_tenant_isolation ON policy.policy_bonus
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON policy.policy_bonus TO app_role;
