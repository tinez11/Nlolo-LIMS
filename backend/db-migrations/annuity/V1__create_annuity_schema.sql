-- db-migrations/annuity/V1__create_annuity_schema.sql
-- Product step 5 (D1): the annuity CONTRACT -- the form bought, the lives, the price, and, once the
-- single premium arrives, the income locked from the rate table in force that day. The module owns
-- the decisions (lock, death, free-look); benefitpayout owns the money (the stream).
CREATE SCHEMA IF NOT EXISTS annuity;
GRANT USAGE ON SCHEMA annuity TO app_role;

CREATE TABLE annuity.contract (
    policy_number                 VARCHAR(30) PRIMARY KEY,
    tenant_id                     UUID NOT NULL,
    product_version_id            UUID NOT NULL,
    status                        VARCHAR(20) NOT NULL CHECK (status IN
        ('AWAITING_PAYMENT','IN_PAYMENT','SURVIVOR','GUARANTEE','ENDED','CANCELLED','LOCK_FAILED')),
    -- The form, COPIED from the version at issue, so a later version can never change a contract.
    form_code                     VARCHAR(30),
    guarantee_years               INTEGER,
    joint                         BOOLEAN NOT NULL DEFAULT false,
    survivor_percent              NUMERIC(9,4),
    escalation_percent            NUMERIC(9,4),
    capital_protected             BOOLEAN NOT NULL DEFAULT false,
    rate_basis                    VARCHAR(10),
    timing                        VARCHAR(10),
    proof_of_life_interval_months INTEGER,
    frequency                     VARCHAR(12),
    annuitant_party_id            UUID NOT NULL,
    joint_life_party_id           UUID,
    purchase_price                NUMERIC(19,2) NOT NULL CHECK (purchase_price > 0),
    currency                      CHAR(3) NOT NULL,
    -- The lock (spec section 4.4): every input and output, set once.
    locked_on                     DATE,
    annuitant_age                 INTEGER,
    joint_age                     INTEGER,
    rate_sex                      VARCHAR(10),
    annual_rate_per_mille         NUMERIC(9,4),
    factor                        NUMERIC(9,4),
    annual_income                 NUMERIC(19,2),
    instalment                    NUMERIC(19,2),
    first_due_date                DATE,
    guarantee_end_date            DATE,
    -- Deaths (Task 6): which claim each was, so an approval redelivered changes nothing twice.
    first_death_party_id          UUID,
    first_death_date              DATE,
    first_death_claim_id          UUID,
    last_death_date               DATE,
    last_death_claim_id           UUID,
    overpayment_owed              NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (overpayment_owed >= 0),
    lock_failure_reason           VARCHAR(500),
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                       BIGINT NOT NULL DEFAULT 0,
    CHECK (status IN ('AWAITING_PAYMENT','CANCELLED','LOCK_FAILED')
           OR (locked_on IS NOT NULL AND instalment > 0 AND first_due_date IS NOT NULL)),
    CHECK (status = 'LOCK_FAILED' OR form_code IS NOT NULL)
);

-- The lock is once-only, even for the owner every migration and test connects as: a locked
-- contract's figures never change.
CREATE OR REPLACE FUNCTION annuity.contract_lock_is_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.locked_on IS NOT NULL AND (NEW.instalment IS DISTINCT FROM OLD.instalment
        OR NEW.annual_income IS DISTINCT FROM OLD.annual_income
        OR NEW.annual_rate_per_mille IS DISTINCT FROM OLD.annual_rate_per_mille
        OR NEW.first_due_date IS DISTINCT FROM OLD.first_due_date
        OR NEW.locked_on IS DISTINCT FROM OLD.locked_on) THEN
        RAISE EXCEPTION 'An annuity''s locked figures never change (policy %)', OLD.policy_number;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER contract_lock_is_final BEFORE UPDATE ON annuity.contract
    FOR EACH ROW EXECUTE FUNCTION annuity.contract_lock_is_final();

ALTER TABLE annuity.contract ENABLE ROW LEVEL SECURITY;
CREATE POLICY contract_tenant_isolation ON annuity.contract
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON annuity.contract TO app_role;
