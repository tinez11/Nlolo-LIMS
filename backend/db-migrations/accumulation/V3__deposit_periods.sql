-- db-migrations/accumulation/V3__deposit_periods.sql
-- A fixed-term deposit's terms (2026-10-02). The balance and every movement stay on V1's ledger;
-- these tables record what each term was agreed at, and what the client asked for at its end.

-- One row per term: period 1 when the deposit arrives, n+1 at each reinvestment. The period IS
-- the record of its rate, so its terms are fixed by trigger once written; only its status, the
-- interest it posted, its closing date and its payout attempts may change.
CREATE TABLE accumulation.deposit_period (
    period_id         UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    policy_number     VARCHAR(20) NOT NULL REFERENCES accumulation.account(policy_number),
    seq               INTEGER NOT NULL CHECK (seq >= 1),
    principal         NUMERIC(19,2) NOT NULL CHECK (principal > 0),
    term_months       INTEGER NOT NULL CHECK (term_months BETWEEN 1 AND 120),
    rate_percent      NUMERIC(7,4) NOT NULL CHECK (rate_percent BETWEEN 0 AND 100),
    -- The version the rate was read from: the policy's own for period 1, the version active for
    -- new business at maturity for a reinvestment (D3, D8).
    rate_version_id   UUID NOT NULL,
    start_date        DATE NOT NULL,
    maturity_date     DATE NOT NULL CHECK (maturity_date > start_date),
    status            VARCHAR(12) NOT NULL DEFAULT 'RUNNING'
                      CHECK (status IN ('RUNNING','MATURED','TERMINATED','CANCELLED')),
    interest_posted   NUMERIC(19,2) CHECK (interest_posted IS NULL OR interest_posted >= 0),
    closed_on         DATE,
    -- The number the deposit was collected from; where the money goes when nobody says otherwise.
    -- Null for a cash receipt, and then a matured deposit waits for staff to record a payee.
    default_payee_ref VARCHAR(200),
    payout_attempts   INTEGER NOT NULL DEFAULT 0 CHECK (payout_attempts >= 0),
    version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT deposit_period_closed_shape CHECK ((status = 'RUNNING') = (closed_on IS NULL)),
    CONSTRAINT ux_deposit_period_seq UNIQUE (policy_number, seq)
);
-- At most one running term per deposit.
CREATE UNIQUE INDEX ux_deposit_period_running ON accumulation.deposit_period (policy_number) WHERE status = 'RUNNING';

CREATE OR REPLACE FUNCTION accumulation.deposit_period_terms_fixed() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.policy_number, NEW.seq, NEW.principal, NEW.term_months, NEW.rate_percent, NEW.rate_version_id,
        NEW.start_date, NEW.maturity_date, NEW.default_payee_ref)
       IS DISTINCT FROM
       (OLD.policy_number, OLD.seq, OLD.principal, OLD.term_months, OLD.rate_percent, OLD.rate_version_id,
        OLD.start_date, OLD.maturity_date, OLD.default_payee_ref) THEN
        RAISE EXCEPTION 'deposit period % is the record of its rate: its terms cannot change', OLD.period_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER deposit_period_terms_fixed BEFORE UPDATE ON accumulation.deposit_period
    FOR EACH ROW EXECUTE FUNCTION accumulation.deposit_period_terms_fixed();

-- app_role has no DELETE grant; this is the owner-level guard, as V1's ledger has.
CREATE OR REPLACE FUNCTION accumulation.deposit_period_never_deleted() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'deposit period % is the record of its rate: it cannot be deleted', OLD.period_id;
END $$;
CREATE TRIGGER deposit_period_never_deleted BEFORE DELETE ON accumulation.deposit_period
    FOR EACH ROW EXECUTE FUNCTION accumulation.deposit_period_never_deleted();

-- What the client asked for at the end of a term (D7). Append-only: a change writes a new row and
-- marks the previous one superseded, so the history of what was asked for is kept.
CREATE TABLE accumulation.maturity_instruction (
    instruction_id UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    policy_number  VARCHAR(20) NOT NULL,
    period_id      UUID NOT NULL REFERENCES accumulation.deposit_period(period_id),
    action         VARCHAR(10) NOT NULL CHECK (action IN ('REINVEST','PAY_OUT')),
    term_months    INTEGER CHECK (term_months BETWEEN 1 AND 120),
    payee_ref      VARCHAR(200),
    recorded_by    VARCHAR(100) NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL,
    superseded_at  TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT maturity_instruction_shape CHECK ((action = 'REINVEST') = (term_months IS NOT NULL))
);
CREATE UNIQUE INDEX ux_maturity_instruction_current ON accumulation.maturity_instruction (period_id)
    WHERE superseded_at IS NULL;

CREATE OR REPLACE FUNCTION accumulation.maturity_instruction_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'maturity instructions are append-only';
    END IF;
    IF OLD.superseded_at IS NOT NULL
       OR (NEW.instruction_id, NEW.policy_number, NEW.period_id, NEW.action, NEW.term_months, NEW.payee_ref,
           NEW.recorded_by, NEW.recorded_at)
          IS DISTINCT FROM
          (OLD.instruction_id, OLD.policy_number, OLD.period_id, OLD.action, OLD.term_months, OLD.payee_ref,
           OLD.recorded_by, OLD.recorded_at) THEN
        RAISE EXCEPTION 'maturity instructions are append-only: only superseding one is allowed';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER maturity_instruction_append_only BEFORE UPDATE OR DELETE ON accumulation.maturity_instruction
    FOR EACH ROW EXECUTE FUNCTION accumulation.maturity_instruction_append_only();

-- The deposit maturity run's selector: running terms whose maturity has come, across tenants. Ids
-- only, as accounts_due_month_end() is; the drain reads everything else under RLS.
CREATE OR REPLACE FUNCTION accumulation.deposits_due()
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT p.policy_number, p.tenant_id FROM accumulation.deposit_period p
     WHERE p.status = 'RUNNING' AND p.maturity_date <= current_date
     ORDER BY p.maturity_date
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION accumulation.deposits_due() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION accumulation.deposits_due() TO app_role;

ALTER TABLE accumulation.deposit_period ENABLE ROW LEVEL SECURITY;
CREATE POLICY deposit_period_tenant_isolation ON accumulation.deposit_period
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE accumulation.maturity_instruction ENABLE ROW LEVEL SECURITY;
CREATE POLICY maturity_instruction_tenant_isolation ON accumulation.maturity_instruction
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON accumulation.deposit_period, accumulation.maturity_instruction TO app_role;
