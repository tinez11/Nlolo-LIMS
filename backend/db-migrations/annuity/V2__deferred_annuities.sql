-- db-migrations/annuity/V2__deferred_annuities.sql
-- Product step 5 (D2): a deferred annuity's contract exists from issue, ACCUMULATING, with no form,
-- price or lock until it vests. The vesting row holds the target, the window, what was confirmed
-- at sale, any hold, and the reminders sent; the instruction table keeps every instruction ever
-- recorded, with one current.
ALTER TABLE annuity.contract DROP CONSTRAINT contract_status_check;
ALTER TABLE annuity.contract ADD CONSTRAINT contract_status_check CHECK (status IN
    ('ACCUMULATING','AWAITING_PAYMENT','IN_PAYMENT','SURVIVOR','GUARANTEE','ENDED','CANCELLED','LOCK_FAILED'));
ALTER TABLE annuity.contract
    ADD COLUMN deferred            BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN vested_on           DATE,
    ADD COLUMN priced_version_id   UUID,
    ADD COLUMN end_reason          VARCHAR(200),
    ALTER COLUMN purchase_price DROP NOT NULL;

-- Before vesting a deferred contract has no form and no price; ended or cancelled before vesting it
-- never will. Every other contract keeps D1's shape. V1's two table CHECKs are unnamed, so Postgres
-- named them contract_check (the lock shape) and contract_check1 (the form) in declaration order.
ALTER TABLE annuity.contract DROP CONSTRAINT contract_check;
ALTER TABLE annuity.contract DROP CONSTRAINT contract_check1;
ALTER TABLE annuity.contract ADD CONSTRAINT contract_lock_shape CHECK (
    status IN ('AWAITING_PAYMENT','CANCELLED','LOCK_FAILED','ACCUMULATING')
    OR (deferred AND vested_on IS NULL AND status = 'ENDED')
    OR (locked_on IS NOT NULL AND instalment > 0 AND first_due_date IS NOT NULL));
ALTER TABLE annuity.contract ADD CONSTRAINT contract_form_shape CHECK (
    status = 'LOCK_FAILED' OR form_code IS NOT NULL OR (deferred AND vested_on IS NULL));
ALTER TABLE annuity.contract ADD CONSTRAINT contract_price_shape CHECK (
    purchase_price > 0 OR (purchase_price IS NULL AND deferred AND vested_on IS NULL));

CREATE TABLE annuity.vesting (
    policy_number           VARCHAR(30) PRIMARY KEY REFERENCES annuity.contract(policy_number),
    tenant_id               UUID NOT NULL,
    -- The ages are kept so a re-confirmed date of birth can move all three dates.
    retirement_age          INTEGER NOT NULL,
    min_vesting_age         INTEGER NOT NULL,
    max_vesting_age         INTEGER NOT NULL,
    target_date             DATE NOT NULL,
    earliest_vesting_date   DATE NOT NULL,
    latest_vesting_date     DATE NOT NULL,
    max_commutation_percent NUMERIC(9,4) NOT NULL,
    default_form_code       VARCHAR(30) NOT NULL,
    default_frequency       VARCHAR(12) NOT NULL,
    confirmed_date_of_birth DATE NOT NULL,
    confirmed_sex           VARCHAR(10),
    age_confirmed_by        VARCHAR(100) NOT NULL,
    age_confirmed_at        TIMESTAMPTZ NOT NULL,
    -- A DEATH claim registered before vesting: the vesting holds until it is rejected (plan R6).
    death_reported_claim_id UUID,
    hold_reason             VARCHAR(500),
    held_at                 TIMESTAMPTZ,
    -- Which vesting date each reminder was sent for, so a deferral re-arms them.
    reminded_90_for         DATE,
    reminded_30_for         DATE,
    vested_balance          NUMERIC(19,2),
    lump_sum                NUMERIC(19,2),
    version                 BIGINT NOT NULL DEFAULT 0,
    CHECK (earliest_vesting_date <= target_date AND target_date <= latest_vesting_date),
    CHECK ((hold_reason IS NULL) = (held_at IS NULL))
);

CREATE TABLE annuity.vesting_instruction (
    instruction_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    policy_number        VARCHAR(30) NOT NULL REFERENCES annuity.vesting(policy_number),
    vesting_date         DATE NOT NULL,
    form_code            VARCHAR(30) NOT NULL,
    frequency            VARCHAR(12) NOT NULL,
    joint_life_party_id  UUID,
    lump_sum_percent     NUMERIC(9,4) NOT NULL CHECK (lump_sum_percent BETWEEN 0 AND 100),
    contributions        VARCHAR(10) CHECK (contributions IN ('CONTINUE','STOP')),
    recorded_by          VARCHAR(100) NOT NULL,
    recorded_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    current              BOOLEAN NOT NULL
);
CREATE UNIQUE INDEX ux_vesting_instruction_current ON annuity.vesting_instruction (policy_number) WHERE current;

-- Contracts due to vest on or before a civil date, across tenants, for the sweep (ids only; the
-- sweep sets the tenant per row and vests each under RLS). The vesting date is the current
-- instruction's, else the target.
CREATE OR REPLACE FUNCTION annuity.vestings_due(as_of DATE)
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT c.policy_number, c.tenant_id
      FROM annuity.contract c
      JOIN annuity.vesting v ON v.policy_number = c.policy_number
      LEFT JOIN annuity.vesting_instruction i ON i.policy_number = c.policy_number AND i.current
     WHERE c.status = 'ACCUMULATING' AND COALESCE(i.vesting_date, v.target_date) <= as_of
     ORDER BY COALESCE(i.vesting_date, v.target_date)
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION annuity.vestings_due(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION annuity.vestings_due(DATE) TO app_role;

-- Contracts whose reminders may be due: vesting date within 90 days, for the reminder pass.
CREATE OR REPLACE FUNCTION annuity.vesting_reminders_due(as_of DATE)
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT c.policy_number, c.tenant_id
      FROM annuity.contract c
      JOIN annuity.vesting v ON v.policy_number = c.policy_number
      LEFT JOIN annuity.vesting_instruction i ON i.policy_number = c.policy_number AND i.current
     WHERE c.status = 'ACCUMULATING' AND COALESCE(i.vesting_date, v.target_date) - as_of BETWEEN 0 AND 90
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION annuity.vesting_reminders_due(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION annuity.vesting_reminders_due(DATE) TO app_role;

ALTER TABLE annuity.vesting ENABLE ROW LEVEL SECURITY;
CREATE POLICY vesting_tenant_isolation ON annuity.vesting
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE annuity.vesting_instruction ENABLE ROW LEVEL SECURITY;
CREATE POLICY vesting_instruction_tenant_isolation ON annuity.vesting_instruction
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON annuity.vesting, annuity.vesting_instruction TO app_role;
