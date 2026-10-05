-- db-migrations/unitlinked/V2__units.sql
-- Product step 6 (U1): each policy's fund split, the orders waiting for a forward price, the append-only unit
-- ledger, the policies frozen against charges, each exit's progress, price-correction adjustments, and the
-- once-a-month notice log. Every unit movement is an entry here; nothing is ever updated or deleted (spec §5).

CREATE TABLE unitlinked.policy_allocation (
    policy_allocation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_number        VARCHAR(30) NOT NULL,
    tenant_id            UUID NOT NULL,
    fund_id              UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    percent              INTEGER NOT NULL CHECK (percent BETWEEN 1 AND 100),
    UNIQUE (tenant_id, policy_number, fund_id)
);

CREATE TABLE unitlinked.pending_order (
    order_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL,
    policy_number      VARCHAR(30) NOT NULL,
    fund_id            UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    side               VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    amount             NUMERIC(19,2) CHECK (amount IS NULL OR amount > 0),
    sell_all           BOOLEAN NOT NULL DEFAULT false,
    purpose            VARCHAR(15) NOT NULL CHECK (purpose IN ('ALLOCATION','CHARGES','DEATH','SURRENDER','MATURITY',
                           'LAPSE','FREE_LOOK','REINVESTMENT')),
    -- A CHARGES order says which charge it sells for; nothing else carries one.
    entry_type         VARCHAR(20) CHECK (entry_type IN ('POLICY_FEE','COST_OF_INSURANCE')),
    -- The forward-pricing binding (spec §5): when it arrived, and the valuation date that fixes, once.
    received_at        TIMESTAMPTZ NOT NULL,
    bound_date         DATE NOT NULL,
    source_type        VARCHAR(30) NOT NULL,
    source_ref         VARCHAR(120) NOT NULL,
    status             VARCHAR(10) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','PRICED','CANCELLED')),
    priced_by_price_id UUID REFERENCES unitlinked.fund_price(price_id),
    priced_at          TIMESTAMPTZ,
    version            BIGINT NOT NULL DEFAULT 0,
    CHECK ((side = 'BUY' AND amount IS NOT NULL AND NOT sell_all)
        OR (side = 'SELL' AND ((amount IS NOT NULL) <> sell_all))),
    CHECK ((purpose = 'CHARGES') = (entry_type IS NOT NULL)),
    CHECK ((status = 'PRICED') = (priced_by_price_id IS NOT NULL AND priced_at IS NOT NULL)),
    UNIQUE (tenant_id, source_type, source_ref, fund_id)
);
CREATE INDEX idx_pending_waiting ON unitlinked.pending_order (tenant_id, fund_id, bound_date) WHERE status = 'WAITING';
CREATE INDEX idx_pending_policy ON unitlinked.pending_order (tenant_id, policy_number, status);

CREATE TABLE unitlinked.unit_entry (
    entry_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    policy_number     VARCHAR(30) NOT NULL,
    fund_id           UUID REFERENCES unitlinked.fund(fund_id),
    entry_type        VARCHAR(20) NOT NULL CHECK (entry_type IN ('ALLOCATION','ALLOCATION_CHARGE','POLICY_FEE',
                          'COST_OF_INSURANCE','DEATH_SALE','SURRENDER_SALE','MATURITY_SALE','LAPSE_SALE','FREE_LOOK_SALE',
                          'CHARGE_REFUND','REINVESTMENT','PRICE_CORRECTION','WRITE_OFF')),
    units             NUMERIC(19,6) NOT NULL DEFAULT 0,
    price             NUMERIC(19,6),
    price_id          UUID REFERENCES unitlinked.fund_price(price_id),
    -- Money: positive into units (a buy), negative out of them (a sale or charge). Money-only entries carry no fund.
    amount            NUMERIC(19,2) NOT NULL,
    valuation_date    DATE NOT NULL,
    bound_date        DATE,
    order_id          UUID REFERENCES unitlinked.pending_order(order_id),
    source_type       VARCHAR(30) NOT NULL,
    source_ref        VARCHAR(120) NOT NULL,
    reverses_entry_id UUID REFERENCES unitlinked.unit_entry(entry_id),
    created_by        VARCHAR(100) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((entry_type IN ('ALLOCATION_CHARGE','CHARGE_REFUND','WRITE_OFF')) = (fund_id IS NULL)),
    CHECK (fund_id IS NULL OR (price IS NOT NULL AND price > 0 AND price_id IS NOT NULL)),
    CHECK (fund_id IS NOT NULL OR units = 0)
);
-- One source, one entry of each type per fund: a redelivered event can never write a movement twice.
CREATE UNIQUE INDEX ux_unit_entry_source ON unitlinked.unit_entry
    (tenant_id, source_type, source_ref, entry_type, COALESCE(fund_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_unit_entry_policy ON unitlinked.unit_entry (tenant_id, policy_number, fund_id);
CREATE INDEX idx_unit_entry_price ON unitlinked.unit_entry (price_id);

CREATE OR REPLACE FUNCTION unitlinked.refuse_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'unitlinked.% is append-only: correct it with a reversing entry, never an %', TG_TABLE_NAME, TG_OP;
END $$;
CREATE TRIGGER unit_entry_append_only BEFORE UPDATE OR DELETE ON unitlinked.unit_entry
    FOR EACH ROW EXECUTE FUNCTION unitlinked.refuse_mutation();

-- A holding never goes below zero, even for a hand-written insert: a sale can only sell units the policy holds.
CREATE OR REPLACE FUNCTION unitlinked.holding_never_negative() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE held NUMERIC(19,6);
BEGIN
    IF NEW.fund_id IS NULL OR NEW.units >= 0 THEN
        RETURN NEW;
    END IF;
    SELECT COALESCE(SUM(units), 0) INTO held FROM unitlinked.unit_entry
     WHERE tenant_id = NEW.tenant_id AND policy_number = NEW.policy_number AND fund_id = NEW.fund_id;
    IF held + NEW.units < 0 THEN
        RAISE EXCEPTION 'policy % would hold % units of fund % (holding %, entry %)',
            NEW.policy_number, held + NEW.units, NEW.fund_id, held, NEW.units;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER unit_entry_holding_never_negative BEFORE INSERT ON unitlinked.unit_entry
    FOR EACH ROW EXECUTE FUNCTION unitlinked.holding_never_negative();

-- No charges while a policy is leaving: a death registered, a surrender approved, a maturity, a lapse, a
-- free-look, or a fund exhausted.
CREATE TABLE unitlinked.frozen_policy (
    -- Policy numbers are unique across tenants (policy.policy's primary key), so the number alone keys it.
    policy_number VARCHAR(30) PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    reason        VARCHAR(15) NOT NULL CHECK (reason IN ('DEATH','SURRENDER','MATURITY','FREE_LOOK','LAPSE','EXHAUSTED')),
    source_ref    VARCHAR(120) NOT NULL,
    frozen_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Each exit and its totals: are all its sales priced, what did they raise, and has it been paid.
CREATE TABLE unitlinked.exit_state (
    exit_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    source_type    VARCHAR(30) NOT NULL,
    source_ref     VARCHAR(120) NOT NULL,
    policy_number  VARCHAR(30) NOT NULL,
    purpose        VARCHAR(12) NOT NULL CHECK (purpose IN ('DEATH','SURRENDER','MATURITY','LAPSE','FREE_LOOK')),
    returned_money NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (returned_money >= 0),
    proceeds       NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (proceeds >= 0),
    status         VARCHAR(14) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PRICED','AWAITING_PAYEE','PAID','REVERSED')),
    payee_ref      VARCHAR(100),
    completed_at   TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, source_type, source_ref)
);

CREATE TABLE unitlinked.price_correction_adjustment (
    adjustment_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL,
    policy_number      VARCHAR(30) NOT NULL,
    corrected_price_id UUID NOT NULL REFERENCES unitlinked.fund_price(price_id),
    amount             NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    direction          VARCHAR(17) NOT NULL CHECK (direction IN ('OWED_TO_CUSTOMER','OWED_BY_CUSTOMER')),
    status             VARCHAR(8) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','SETTLED','WAIVED')),
    proposed_by        VARCHAR(100),
    decided_by         VARCHAR(100),
    decided_at         TIMESTAMPTZ,
    reason             VARCHAR(500),
    version            BIGINT NOT NULL DEFAULT 0,
    CHECK (decided_by IS NULL OR proposed_by IS NULL OR decided_by <> proposed_by),
    UNIQUE (corrected_price_id, policy_number)
);

-- What the ledger carries for each fund's units, after the last pricing run: exactly units in issue x that run's
-- price, rounded once. Each run's revaluation is the true-up from what the run's own movements left carried to
-- that figure, so price movement and every sub-cent residue land in one posting and 2150 never drifts (plan D1).
CREATE TABLE unitlinked.fund_liability (
    fund_id        UUID PRIMARY KEY REFERENCES unitlinked.fund(fund_id),
    tenant_id      UUID NOT NULL,
    carried        NUMERIC(19,2) NOT NULL,
    price_id       UUID NOT NULL REFERENCES unitlinked.fund_price(price_id),
    units_in_issue NUMERIC(19,6) NOT NULL,
    version        BIGINT NOT NULL DEFAULT 0
);

-- Notices sent at most once a month per policy (low fund).
CREATE TABLE unitlinked.notice_log (
    notice_log_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(30) NOT NULL,
    kind          VARCHAR(20) NOT NULL,
    month         CHAR(7) NOT NULL,
    UNIQUE (tenant_id, policy_number, kind, month)
);

-- Every unit-linked policy across tenants, for the nightly sweeps (charges, maturity). SECURITY DEFINER, as
-- policy.funeral_policies_with_active_lives() is: a sweep runs with no tenant set, which RLS would read as none.
-- Ids only: each policy is then worked under its own tenant.
CREATE OR REPLACE FUNCTION unitlinked.unit_linked_policies()
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT DISTINCT a.policy_number, a.tenant_id
      FROM unitlinked.policy_allocation a
     ORDER BY a.policy_number;
$$;
REVOKE EXECUTE ON FUNCTION unitlinked.unit_linked_policies() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION unitlinked.unit_linked_policies() TO app_role;

ALTER TABLE unitlinked.policy_allocation ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_allocation_tenant_isolation ON unitlinked.policy_allocation
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE unitlinked.pending_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY pending_order_tenant_isolation ON unitlinked.pending_order
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE unitlinked.unit_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY unit_entry_tenant_isolation ON unitlinked.unit_entry
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE unitlinked.frozen_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY frozen_policy_tenant_isolation ON unitlinked.frozen_policy
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE unitlinked.exit_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY exit_state_tenant_isolation ON unitlinked.exit_state
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE unitlinked.price_correction_adjustment ENABLE ROW LEVEL SECURITY;
CREATE POLICY price_correction_adjustment_tenant_isolation ON unitlinked.price_correction_adjustment
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE unitlinked.fund_liability ENABLE ROW LEVEL SECURITY;
CREATE POLICY fund_liability_tenant_isolation ON unitlinked.fund_liability
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE unitlinked.notice_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY notice_log_tenant_isolation ON unitlinked.notice_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON unitlinked.unit_entry, unitlinked.notice_log TO app_role;
GRANT SELECT, INSERT, UPDATE ON unitlinked.policy_allocation, unitlinked.pending_order, unitlinked.exit_state,
    unitlinked.price_correction_adjustment, unitlinked.fund_liability TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON unitlinked.frozen_policy TO app_role;
