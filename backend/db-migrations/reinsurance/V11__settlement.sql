-- db-migrations/reinsurance/V11__settlement.sql
-- Settling an approved statement (2026-11-08). The statement posts what the quarter owes into the current account
-- (1434), the profit commission receivable (1433) and funds withheld (2550); until now nothing took them out again but a
-- free-form manual journal. A settlement is one movement against one statement, recorded by one finance person and
-- confirmed by a second (or by the recorder holding reinsurance.approve-own, then acted_alone):
--   RECEIVED            money the reinsurer paid us against the current account      Dr receipt bank / Cr 1434
--   PAID                money we pay the reinsurer -- an EFT under "Payments to make" Dr 1434 / Cr bank, once made
--   PROFIT_COMMISSION   profit commission the reinsurer paid us                       Dr receipt bank / Cr 1433
--   WITHHELD_RELEASED   funds withheld handed back into the current account           Dr 2550 / Cr 1434
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

CREATE TABLE IF NOT EXISTS reinsurance.settlement (
    settlement_id        UUID PRIMARY KEY,
    tenant_id            UUID          NOT NULL,
    statement_id         UUID          NOT NULL REFERENCES reinsurance.statement (statement_id),
    treaty_id            UUID          NOT NULL,
    kind                 VARCHAR(20)   NOT NULL CHECK (kind IN ('RECEIVED', 'PAID', 'PROFIT_COMMISSION', 'WITHHELD_RELEASED')),
    amount               NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency             CHAR(3)       NOT NULL,
    -- How money came in (RECEIVED, PROFIT_COMMISSION): BANK_DEPOSIT, CHEQUE ... and its reference and day.
    method               VARCHAR(20),
    reference            VARCHAR(100),
    received_on          DATE,
    -- Where money goes out (PAID): the reinsurer's bank account.
    payee_bank           VARCHAR(100),
    payee_branch         VARCHAR(100),
    payee_account_name   VARCHAR(200),
    payee_account_number VARCHAR(50),
    note                 VARCHAR(500),
    status               VARCHAR(20)   NOT NULL CHECK (status IN ('AWAITING_APPROVAL', 'POSTED', 'REQUESTED', 'PAID',
                                                                  'FAILED', 'REFUSED')),
    recorded_by          VARCHAR(100)  NOT NULL,
    recorded_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_by           VARCHAR(100),
    decided_at           TIMESTAMPTZ,
    decision_reason      VARCHAR(500),
    acted_alone          BOOLEAN       NOT NULL DEFAULT false,
    CONSTRAINT settlement_decider_not_recorder CHECK (decided_by IS NULL OR decided_by <> recorded_by OR acted_alone),
    CONSTRAINT settlement_money_in_says_how CHECK (kind NOT IN ('RECEIVED', 'PROFIT_COMMISSION')
        OR (method IS NOT NULL AND reference IS NOT NULL AND received_on IS NOT NULL)),
    CONSTRAINT settlement_money_out_says_where CHECK (kind <> 'PAID'
        OR (payee_bank IS NOT NULL AND payee_account_name IS NOT NULL AND payee_account_number IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_settlement_statement ON reinsurance.settlement (tenant_id, statement_id);
CREATE INDEX IF NOT EXISTS idx_settlement_waiting ON reinsurance.settlement (tenant_id, status);

ALTER TABLE reinsurance.settlement ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS settlement_tenant_isolation ON reinsurance.settlement;
CREATE POLICY settlement_tenant_isolation ON reinsurance.settlement
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON reinsurance.settlement TO app_role;
