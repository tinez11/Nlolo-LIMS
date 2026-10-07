-- IFRS 17 I5b: expense allocation (guide P-19, month-end step 5). Finance types the month's three totals; the platform
-- spreads them over the groups of insurance contracts by driver; a FINANCE_APPROVER who did not prepare it approves, and
-- one SYSTEM journal posts Dr 5210 / 5215 / 2123 or 5310, Cr 8490. A nil allocation ("none this month") posts nothing
-- but is decided like any other: the extract waits for a POSTED allocation.

CREATE TABLE finaccounting.expense_allocation (
    allocation_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    period               VARCHAR(7) NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    status               VARCHAR(8) NOT NULL CHECK (status IN ('PREPARED','POSTED','REJECTED','REPLACED')),
    maintenance          NUMERIC(19,2) NOT NULL CHECK (maintenance >= 0),
    claims_handling      NUMERIC(19,2) NOT NULL CHECK (claims_handling >= 0),
    acquisition          NUMERIC(19,2) NOT NULL CHECK (acquisition >= 0),
    currency             CHAR(3) NOT NULL DEFAULT 'TZS',
    study_reference      VARCHAR(200),
    note                 VARCHAR(500),
    nil_reason           VARCHAR(500),
    prepared_by          VARCHAR(100) NOT NULL,
    prepared_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by           VARCHAR(100),
    decided_at           TIMESTAMPTZ,
    decision_reason      VARCHAR(500),
    pool_at_approval     NUMERIC(19,2),
    over_pool            BOOLEAN,
    replaces_id          UUID REFERENCES finaccounting.expense_allocation (allocation_id),
    journal_entry_id     UUID,
    reversal_journal_id  UUID,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT expense_allocation_decider_not_preparer CHECK (decided_by IS NULL OR decided_by <> prepared_by),
    CONSTRAINT expense_allocation_nil_has_reason
        CHECK (maintenance + claims_handling + acquisition > 0 OR nil_reason IS NOT NULL),
    CONSTRAINT expense_allocation_totals_have_study
        CHECK (maintenance + claims_handling + acquisition = 0 OR study_reference IS NOT NULL)
);
CREATE UNIQUE INDEX ux_expense_allocation_one_prepared ON finaccounting.expense_allocation (tenant_id, period)
    WHERE status = 'PREPARED';
CREATE UNIQUE INDEX ux_expense_allocation_one_posted ON finaccounting.expense_allocation (tenant_id, period)
    WHERE status = 'POSTED';

CREATE TABLE finaccounting.expense_allocation_line (
    allocation_id      UUID NOT NULL REFERENCES finaccounting.expense_allocation (allocation_id),
    tenant_id          UUID NOT NULL,
    group_key          VARCHAR(40) NOT NULL,
    measurement_model  VARCHAR(5) NOT NULL,
    category           VARCHAR(15) NOT NULL CHECK (category IN ('MAINTENANCE','CLAIMS_HANDLING','ACQUISITION')),
    account_code       VARCHAR(10) NOT NULL,
    driver             VARCHAR(10) NOT NULL CHECK (driver IN ('IN_FORCE','CLAIMS','ISSUED','EQUAL')),
    driver_count       BIGINT NOT NULL,
    amount             NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (allocation_id, group_key, category)
);

-- The allocation a journal posts (or reverses); the pool leaves these journals out.
ALTER TABLE finaccounting.journal_entry
    ADD COLUMN expense_allocation_id UUID REFERENCES finaccounting.expense_allocation (allocation_id);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['expense_allocation','expense_allocation_line'] LOOP
        EXECUTE format('ALTER TABLE finaccounting.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON finaccounting.%I USING (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)',
                       t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON finaccounting.expense_allocation TO app_role;
GRANT SELECT, INSERT ON finaccounting.expense_allocation_line TO app_role;
