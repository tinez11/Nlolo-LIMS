-- 2026-11-14, shareholders register (spec 2026-11-14-shareholders-register-design.md): who holds the company's shares,
-- each issue of shares recorded by one person and approved by a second (or by the recorder holding ledger.approve-own,
-- acted_alone), and the minimum paid-up capital finance enters from TIRA. An approved issue posts its journal; nothing
-- here is edited after approval. Re-runnable: migrate.sh is not idempotent.

CREATE TABLE IF NOT EXISTS finaccounting.shareholder (
    shareholder_id   UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    name             VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    kind             VARCHAR(8) NOT NULL CHECK (kind IN ('PERSON','COMPANY')),
    tanzanian        BOOLEAN NOT NULL,
    id_number        VARCHAR(60) NOT NULL CHECK (length(trim(id_number)) > 0),
    phone            VARCHAR(40),
    address          VARCHAR(300),
    created_by       VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id_number)
);

CREATE TABLE IF NOT EXISTS finaccounting.share_class (
    tenant_id        UUID NOT NULL,
    class_code       VARCHAR(10) NOT NULL CHECK (class_code IN ('ORDINARY','PREFERENCE')),
    nominal_value    NUMERIC(19,2) NOT NULL CHECK (nominal_value > 0),
    set_by           VARCHAR(100) NOT NULL,
    set_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, class_code)
);

CREATE TABLE IF NOT EXISTS finaccounting.share_issue (
    issue_id         UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    shareholder_id   UUID NOT NULL REFERENCES finaccounting.shareholder (shareholder_id),
    class_code       VARCHAR(10) NOT NULL,
    shares           BIGINT NOT NULL CHECK (shares > 0),
    price_per_share  NUMERIC(19,2) NOT NULL CHECK (price_per_share > 0),
    nominal_value    NUMERIC(19,2) NOT NULL CHECK (nominal_value > 0),
    amount_paid      NUMERIC(19,2) NOT NULL CHECK (amount_paid > 0),
    received_on      DATE NOT NULL,
    bank_account     VARCHAR(20) NOT NULL,
    reference        VARCHAR(200) NOT NULL CHECK (length(trim(reference)) > 0),
    status           VARCHAR(10) NOT NULL DEFAULT 'PROPOSED'
                     CHECK (status IN ('PROPOSED','APPROVED','REJECTED','WITHDRAWN')),
    proposed_by      VARCHAR(100) NOT NULL,
    proposed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by       VARCHAR(100),
    decided_at       TIMESTAMPTZ,
    decision_reason  VARCHAR(500),
    journal_entry_id UUID,
    acted_alone      BOOLEAN NOT NULL DEFAULT false,
    FOREIGN KEY (tenant_id, class_code) REFERENCES finaccounting.share_class (tenant_id, class_code),
    CONSTRAINT share_issue_two_person CHECK (decided_by IS NULL OR decided_by <> proposed_by OR acted_alone
        OR status = 'WITHDRAWN'),
    CONSTRAINT share_issue_approved_posted CHECK (status <> 'APPROVED' OR journal_entry_id IS NOT NULL),
    CONSTRAINT share_issue_not_below_nominal CHECK (price_per_share >= nominal_value)
);
CREATE INDEX IF NOT EXISTS share_issue_tenant_status ON finaccounting.share_issue (tenant_id, status);

-- Append-only: each change is a row and the latest is in force, so the rows are its history. NULL = cleared, Not set.
CREATE TABLE IF NOT EXISTS finaccounting.capital_minimum (
    tenant_id        UUID NOT NULL,
    set_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    minimum_paid_up  NUMERIC(19,2) CHECK (minimum_paid_up IS NULL OR minimum_paid_up > 0),
    set_by           VARCHAR(100) NOT NULL,
    PRIMARY KEY (tenant_id, set_at)
);

ALTER TABLE finaccounting.shareholder ENABLE ROW LEVEL SECURITY;
ALTER TABLE finaccounting.share_class ENABLE ROW LEVEL SECURITY;
ALTER TABLE finaccounting.share_issue ENABLE ROW LEVEL SECURITY;
ALTER TABLE finaccounting.capital_minimum ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS shareholder_tenant_isolation ON finaccounting.shareholder;
CREATE POLICY shareholder_tenant_isolation ON finaccounting.shareholder
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
DROP POLICY IF EXISTS share_class_tenant_isolation ON finaccounting.share_class;
CREATE POLICY share_class_tenant_isolation ON finaccounting.share_class
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
DROP POLICY IF EXISTS share_issue_tenant_isolation ON finaccounting.share_issue;
CREATE POLICY share_issue_tenant_isolation ON finaccounting.share_issue
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
DROP POLICY IF EXISTS capital_minimum_tenant_isolation ON finaccounting.capital_minimum;
CREATE POLICY capital_minimum_tenant_isolation ON finaccounting.capital_minimum
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON finaccounting.shareholder TO app_role;
GRANT SELECT, INSERT, UPDATE ON finaccounting.share_class TO app_role;
GRANT SELECT, INSERT, UPDATE ON finaccounting.share_issue TO app_role;
GRANT SELECT, INSERT ON finaccounting.capital_minimum TO app_role;
