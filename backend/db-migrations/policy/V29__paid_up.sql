-- Paid-up: the customer stops paying and keeps reduced cover instead of losing it (product step 1,
-- task 3; guide §21.3, "Paid-up / surrender"). A new terminal-for-premiums but in-force status, and
-- a small table recording how far premiums are paid, which the proportionate paid-up sum assured is
-- computed from.

-- PAID_UP joins the status set. In force for cover, but no premium is due -- Policy.wasOnRiskOn and
-- isInForce treat it as on risk, billing terminates its schedule. V11/V27 convention: the CHECK is
-- rewritten in full and Policy's state machine decides which transitions are legal.
ALTER TABLE policy.policy DROP CONSTRAINT IF EXISTS policy_status_check;
ALTER TABLE policy.policy ADD CONSTRAINT policy_status_check
    CHECK (status IN ('PROPOSED','ACTIVE','LAPSED','SUSPENDED','SURRENDERED','MATURED','REINSTATED','NOT_TAKEN_UP','EXPIRED','PAID_UP'));

COMMENT ON COLUMN policy.policy.status IS
    'PROPOSED awaits first premium. NOT_TAKEN_UP: the offer expired unpaid. EXPIRED: a termed policy ran its full term and paid nothing. PAID_UP: the customer stopped paying and keeps reduced cover -- in force, no premium due. Only ACTIVE, REINSTATED and PAID_UP are in force.';

-- How far premiums are paid, and when that was last computed. Written only for savings policies (the
-- cash-value recompute upserts it on each premium), so no existing policy or its tests are touched.
-- paid_to_date drives both the cash-value policy year and the proportionate paid-up sum assured.
CREATE TABLE policy.policy_value (
    policy_number   VARCHAR(20) PRIMARY KEY REFERENCES policy.policy(policy_number),
    tenant_id       UUID NOT NULL,
    paid_to_date    DATE,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE policy.policy_value ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_value_tenant_isolation ON policy.policy_value
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON policy.policy_value TO app_role;
