-- db-migrations/product/V32__account_charges.sql
-- Account charges staff create and choose per savings policy (2026-10-09, the user's decisions on the Mkakati test:
-- "a charge section where staff can create charges and choose which one on issuing", flat or a percentage, with when
-- it is taken; finance officers and admins keep them; whether a charge may take a balance below the product's minimum
-- "depends on organization").
--
-- An account-based version already carries its own charges by policy year (V19 accumulation_charge). A policy issued
-- with charges chosen from this list is charged by those instead, for its whole life; a policy with none chosen keeps
-- its version's charges, as before.
--
-- charge_when:  DEPOSIT     each deposit -- the first, top-ups and transfers in    (percent of the deposit)
--               WITHDRAWAL  each withdrawal, from the balance left                 (percent of the amount withdrawn)
--               MONTHLY     each month-end on cover                                (percent of the balance)
--               YEARLY      the month-end after each policy anniversary            (percent of the balance)
--               OPENING     once, on the first deposit                            (percent of that deposit)
--               MATURITY    once, when the account pays out at maturity           (percent of the balance)
-- Charges are never deleted -- a policy keeps what it was issued with -- only withdrawn from the choice.

CREATE TABLE product.account_charge (
    charge_id     UUID PRIMARY KEY,
    tenant_id     UUID          NOT NULL,
    name          VARCHAR(120)  NOT NULL,
    description   VARCHAR(500),
    charge_when   VARCHAR(12)   NOT NULL
        CHECK (charge_when IN ('DEPOSIT', 'WITHDRAWAL', 'MONTHLY', 'YEARLY', 'OPENING', 'MATURITY')),
    amount_type   VARCHAR(7)    NOT NULL CHECK (amount_type IN ('FLAT', 'PERCENT')),
    amount        NUMERIC(18, 4) NOT NULL CHECK (amount > 0),
    currency      CHAR(3)       NOT NULL,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by    VARCHAR(255)  NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_account_charge_percent CHECK (amount_type <> 'PERCENT' OR amount <= 100)
);
CREATE UNIQUE INDEX ux_account_charge_name ON product.account_charge (tenant_id, lower(name));

-- One row per organisation (tenant): may a charge take an account below its product's minimum balance?
-- No row reads as "no" -- the balance a withdrawal may not cross, a charge may not cross either.
CREATE TABLE product.account_charge_setting (
    tenant_id                UUID PRIMARY KEY,
    may_go_below_minimum     BOOLEAN      NOT NULL,
    updated_by               VARCHAR(255) NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0
);

ALTER TABLE product.account_charge ENABLE ROW LEVEL SECURITY;
CREATE POLICY account_charge_tenant_isolation ON product.account_charge
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
ALTER TABLE product.account_charge_setting ENABLE ROW LEVEL SECURITY;
CREATE POLICY account_charge_setting_tenant_isolation ON product.account_charge_setting
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON product.account_charge TO app_role;
GRANT SELECT, INSERT, UPDATE ON product.account_charge_setting TO app_role;
