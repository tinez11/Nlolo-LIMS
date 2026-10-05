-- db-migrations/unitlinked/V3__u2.sql
-- Product step 6, U2: fund switches, partial withdrawals, top-ups, premium redirection and unit statements, and the
-- movements they write. Constraint names are U1's own (read from the database before writing this, plan D-check).

-- ---- The new movements -------------------------------------------------------------------------------------------
ALTER TABLE unitlinked.unit_entry DROP CONSTRAINT unit_entry_entry_type_check;
ALTER TABLE unitlinked.unit_entry ADD CONSTRAINT unit_entry_entry_type_check CHECK (entry_type IN
    ('ALLOCATION','ALLOCATION_CHARGE','POLICY_FEE','COST_OF_INSURANCE','DEATH_SALE','SURRENDER_SALE','MATURITY_SALE',
     'LAPSE_SALE','FREE_LOOK_SALE','CHARGE_REFUND','REINVESTMENT','PRICE_CORRECTION','WRITE_OFF',
     'SWITCH_OUT','SWITCH_IN','SWITCH_FEE','WITHDRAWAL_SALE','SURRENDER_CHARGE'));
-- Money-only entries have no fund; every other one has.
ALTER TABLE unitlinked.unit_entry DROP CONSTRAINT unit_entry_check;
ALTER TABLE unitlinked.unit_entry ADD CONSTRAINT unit_entry_money_only_check CHECK (
    (entry_type IN ('ALLOCATION_CHARGE','CHARGE_REFUND','WRITE_OFF','SWITCH_FEE','SURRENDER_CHARGE')) = (fund_id IS NULL));
ALTER TABLE unitlinked.pending_order DROP CONSTRAINT pending_order_purpose_check;
ALTER TABLE unitlinked.pending_order ADD CONSTRAINT pending_order_purpose_check CHECK (purpose IN
    ('ALLOCATION','CHARGES','DEATH','SURRENDER','MATURITY','LAPSE','FREE_LOOK','REINVESTMENT','WITHDRAWAL'));
-- The surrender charge an exit (a surrender, a non-payment lapse) took from its proceeds (spec Q6/Q7).
ALTER TABLE unitlinked.exit_state ADD COLUMN surrender_charge NUMERIC(19,2) NOT NULL DEFAULT 0
    CHECK (surrender_charge >= 0);

-- ---- Premium redirection: the split as a history (spec §4) --------------------------------------------------------
-- U1's policy_allocation stays (nothing reads it after this) and becomes each policy's first row here.
CREATE TABLE unitlinked.premium_split (
    split_id       UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    policy_number  VARCHAR(20) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    recorded_by    VARCHAR(100) NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL
);
CREATE TABLE unitlinked.premium_split_fund (
    split_id UUID NOT NULL REFERENCES unitlinked.premium_split(split_id),
    fund_id  UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    percent  INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 100),
    PRIMARY KEY (split_id, fund_id)
);
CREATE INDEX idx_premium_split_policy ON unitlinked.premium_split (tenant_id, policy_number, effective_from);
INSERT INTO unitlinked.premium_split (split_id, tenant_id, policy_number, effective_from, recorded_by, recorded_at)
    SELECT gen_random_uuid(), a.tenant_id, a.policy_number, TIMESTAMPTZ '1970-01-01 00:00:00+00', 'migration:unitlinked/V3', now()
    FROM (SELECT DISTINCT tenant_id, policy_number FROM unitlinked.policy_allocation) a;
INSERT INTO unitlinked.premium_split_fund (split_id, fund_id, percent)
    SELECT s.split_id, a.fund_id, a.percent FROM unitlinked.policy_allocation a
    JOIN unitlinked.premium_split s ON s.tenant_id = a.tenant_id AND s.policy_number = a.policy_number;

-- ---- Fund switches (spec §2) ----------------------------------------------------------------------------------------
CREATE TABLE unitlinked.switch_request (
    switch_id      UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    policy_number  VARCHAR(20) NOT NULL,
    requested_at   TIMESTAMPTZ NOT NULL,
    requested_by   VARCHAR(100) NOT NULL,
    -- Both legs on one date: the LATEST of the involved funds' bindings (spec Q1).
    bound_date     DATE NOT NULL,
    status         VARCHAR(10) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','EXECUTED','CANCELLED')),
    executed_on    DATE,
    fee            NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    version        BIGINT NOT NULL DEFAULT 0,
    CHECK ((status = 'EXECUTED') = (executed_on IS NOT NULL))
);
CREATE UNIQUE INDEX ux_switch_waiting ON unitlinked.switch_request (tenant_id, policy_number) WHERE status = 'WAITING';
CREATE TABLE unitlinked.switch_leg (
    switch_id UUID NOT NULL REFERENCES unitlinked.switch_request(switch_id),
    fund_id   UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    side      VARCHAR(3) NOT NULL CHECK (side IN ('OUT','IN')),
    percent   INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 100),
    PRIMARY KEY (switch_id, fund_id, side)
);

-- ---- Partial withdrawals (spec §3) -----------------------------------------------------------------------------------
CREATE TABLE unitlinked.withdrawal_request (
    withdrawal_id    UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    policy_number    VARCHAR(20) NOT NULL,
    gross_amount     NUMERIC(19,2) NOT NULL CHECK (gross_amount > 0),
    payee_ref        VARCHAR(100) NOT NULL,
    status           VARCHAR(10) NOT NULL DEFAULT 'REQUESTED'
                         CHECK (status IN ('REQUESTED','APPROVED','PRICED','PAID','CANCELLED')),
    requested_by     VARCHAR(100) NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL,
    approved_by      VARCHAR(100),
    approved_at      TIMESTAMPTZ,
    proceeds         NUMERIC(19,2) NOT NULL DEFAULT 0,
    surrender_charge NUMERIC(19,2) NOT NULL DEFAULT 0,
    shortfall        NUMERIC(19,2) NOT NULL DEFAULT 0,
    version          BIGINT NOT NULL DEFAULT 0,
    -- The two-person rule, held by the database too.
    CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
CREATE UNIQUE INDEX ux_withdrawal_live ON unitlinked.withdrawal_request (tenant_id, policy_number)
    WHERE status IN ('REQUESTED','APPROVED');
-- Named funds only; none = pro rata at approval (spec Q3).
CREATE TABLE unitlinked.withdrawal_fund (
    withdrawal_id UUID NOT NULL REFERENCES unitlinked.withdrawal_request(withdrawal_id),
    fund_id       UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (withdrawal_id, fund_id)
);

-- ---- Top-ups (spec §4) ---------------------------------------------------------------------------------------------------
CREATE TABLE unitlinked.top_up (
    top_up_id     UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency      CHAR(3) NOT NULL,
    payer_ref     VARCHAR(100) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','RECEIVED','REFUNDED','FAILED')),
    requested_by  VARCHAR(100) NOT NULL,
    requested_at  TIMESTAMPTZ NOT NULL,
    received_at   TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0
);
-- Its own split, when given; none = the split in force when the money is received.
CREATE TABLE unitlinked.top_up_fund (
    top_up_id UUID NOT NULL REFERENCES unitlinked.top_up(top_up_id),
    fund_id   UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    percent   INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 100),
    PRIMARY KEY (top_up_id, fund_id)
);

-- One row per Idempotency-Key on a request that creates something (accumulation V2's reason: a retried top-up was
-- once collected and credited twice).
CREATE TABLE unitlinked.request_key (
    tenant_id       UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    operation       VARCHAR(30) NOT NULL,
    target          VARCHAR(40) NOT NULL,
    created_id      UUID NOT NULL,
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key)
);

-- ---- Statements (spec §5) ------------------------------------------------------------------------------------------------
CREATE TABLE unitlinked.unit_statement (
    statement_id  UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    period_from   DATE NOT NULL,
    period_to     DATE NOT NULL CHECK (period_to >= period_from),
    kind          VARCHAR(9) NOT NULL CHECK (kind IN ('ANNUAL','ON_DEMAND')),
    document_ref  VARCHAR(200) NOT NULL,
    generated_by  VARCHAR(100) NOT NULL,
    generated_at  TIMESTAMPTZ NOT NULL
);
-- The annual statement is filed once per policy and year; on-demand ones are as many as staff ask for.
CREATE UNIQUE INDEX ux_unit_statement_annual ON unitlinked.unit_statement (tenant_id, policy_number, period_to)
    WHERE kind = 'ANNUAL';

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['premium_split','switch_request','withdrawal_request','top_up','request_key','unit_statement'] LOOP
        EXECUTE format('ALTER TABLE unitlinked.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON unitlinked.%I USING (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)',
                       t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON unitlinked.switch_request, unitlinked.withdrawal_request, unitlinked.top_up TO app_role;
GRANT SELECT, INSERT ON unitlinked.premium_split, unitlinked.premium_split_fund, unitlinked.switch_leg,
    unitlinked.withdrawal_fund, unitlinked.top_up_fund, unitlinked.request_key, unitlinked.unit_statement TO app_role;
