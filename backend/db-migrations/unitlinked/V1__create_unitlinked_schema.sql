-- db-migrations/unitlinked/V1__create_unitlinked_schema.sql
-- Product step 6 (U1): the insurer's fund register and its prices. A price is entered by one person and
-- approved by a second, never before its valuation date's cut-off, and never edited or deleted once approved
-- -- a mistake is corrected by a superseding price, which re-runs every movement priced on it (spec §3).
CREATE SCHEMA IF NOT EXISTS unitlinked;
GRANT USAGE ON SCHEMA unitlinked TO app_role;

CREATE TABLE unitlinked.fund (
    fund_id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                        UUID NOT NULL,
    code                             VARCHAR(20) NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$'),
    name                             VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    currency                         CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    asset_class                      VARCHAR(15) NOT NULL CHECK (asset_class IN ('EQUITY','BOND','MONEY_MARKET','BALANCED')),
    -- Disclosure only: the charge is already netted into the price the fund manager publishes (spec Q7).
    annual_management_charge_percent NUMERIC(7,4) NOT NULL
        CHECK (annual_management_charge_percent >= 0 AND annual_management_charge_percent < 100),
    -- Africa/Dar_es_Salaam civil time. An order received before it on day D is priced at D's price.
    cut_off_time                     TIME NOT NULL,
    status                           VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    created_by                       VARCHAR(100) NOT NULL,
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_by                        VARCHAR(100),
    closed_at                        TIMESTAMPTZ,
    version                          BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, code),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE TABLE unitlinked.fund_price (
    price_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    fund_id             UUID NOT NULL REFERENCES unitlinked.fund(fund_id),
    valuation_date      DATE NOT NULL,
    price               NUMERIC(19,6) NOT NULL CHECK (price > 0),
    status              VARCHAR(12) NOT NULL CHECK (status IN ('PROPOSED','APPROVED','SUPERSEDED','WITHDRAWN')),
    move_reason         VARCHAR(500),
    supersedes_price_id UUID REFERENCES unitlinked.fund_price(price_id),
    proposed_by         VARCHAR(100) NOT NULL,
    proposed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by         VARCHAR(100),
    approved_at         TIMESTAMPTZ,
    version             BIGINT NOT NULL DEFAULT 0,
    -- Two people, enforced by the database as well as the service.
    CHECK (approved_by IS NULL OR approved_by <> proposed_by),
    CHECK ((status IN ('APPROVED','SUPERSEDED')) = (approved_by IS NOT NULL AND approved_at IS NOT NULL))
);
-- One price in force per fund and date; one live proposal per fund and date.
CREATE UNIQUE INDEX ux_fund_price_approved ON unitlinked.fund_price (fund_id, valuation_date) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX ux_fund_price_proposed ON unitlinked.fund_price (fund_id, valuation_date) WHERE status = 'PROPOSED';
CREATE INDEX idx_fund_price_lookup ON unitlinked.fund_price (tenant_id, fund_id, status, valuation_date);

-- An approved price's figures never change and no price is ever deleted, even for the owner every migration
-- and test connects as. The only moves out of APPROVED are to SUPERSEDED (a correction); SUPERSEDED is final.
CREATE OR REPLACE FUNCTION unitlinked.price_is_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Fund prices are never deleted (price %)', OLD.price_id;
    END IF;
    IF OLD.status IN ('APPROVED','SUPERSEDED') AND (NEW.price IS DISTINCT FROM OLD.price
        OR NEW.valuation_date IS DISTINCT FROM OLD.valuation_date
        OR NEW.fund_id IS DISTINCT FROM OLD.fund_id
        OR NEW.approved_by IS DISTINCT FROM OLD.approved_by
        OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
        OR (OLD.status = 'SUPERSEDED' AND NEW.status <> 'SUPERSEDED')
        OR (OLD.status = 'APPROVED' AND NEW.status NOT IN ('APPROVED','SUPERSEDED'))) THEN
        RAISE EXCEPTION 'An approved fund price never changes (price %); correct it with a superseding price', OLD.price_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER fund_price_is_final BEFORE UPDATE ON unitlinked.fund_price
    FOR EACH ROW EXECUTE FUNCTION unitlinked.price_is_final();
CREATE TRIGGER fund_price_no_delete BEFORE DELETE ON unitlinked.fund_price
    FOR EACH ROW EXECUTE FUNCTION unitlinked.price_is_final();

ALTER TABLE unitlinked.fund ENABLE ROW LEVEL SECURITY;
CREATE POLICY fund_tenant_isolation ON unitlinked.fund
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE unitlinked.fund_price ENABLE ROW LEVEL SECURITY;
CREATE POLICY fund_price_tenant_isolation ON unitlinked.fund_price
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON unitlinked.fund, unitlinked.fund_price TO app_role;
