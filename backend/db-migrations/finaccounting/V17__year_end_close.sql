-- IFRS 17 I6: the year-end close (guide 5.7, M-07, M-11). A FINANCE_OFFICER prepares the close of a calendar year; a
-- FINANCE_APPROVER who did not prepare it approves; one SYSTEM journal in Y-12 clears classes 4-8 to 3310, moves the
-- result to 3210 and closes dividends declared (3320). December cannot lock without it when the year has anything to
-- close; a later replacement reverses it.

CREATE TABLE finaccounting.year_end_close (
    close_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL,
    year                 SMALLINT NOT NULL CHECK (year BETWEEN 1900 AND 2999),
    status               VARCHAR(8) NOT NULL CHECK (status IN ('PREPARED','POSTED','REJECTED','REPLACED')),
    class_totals         JSONB,
    profit               NUMERIC(19,2),
    dividends            NUMERIC(19,2),
    prepared_by          VARCHAR(100) NOT NULL,
    prepared_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by           VARCHAR(100),
    decided_at           TIMESTAMPTZ,
    decision_reason      VARCHAR(500),
    replaces_id          UUID REFERENCES finaccounting.year_end_close (close_id),
    journal_entry_id     UUID,
    reversal_journal_id  UUID,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT year_end_close_decider_not_preparer CHECK (decided_by IS NULL OR decided_by <> prepared_by)
);
CREATE UNIQUE INDEX ux_year_end_close_one_prepared ON finaccounting.year_end_close (tenant_id, year)
    WHERE status = 'PREPARED';
CREATE UNIQUE INDEX ux_year_end_close_one_posted ON finaccounting.year_end_close (tenant_id, year)
    WHERE status = 'POSTED';

-- The close a journal posts or reverses; the extract, the expense pool and the year's balances leave these out.
ALTER TABLE finaccounting.journal_entry
    ADD COLUMN year_end_close_id UUID REFERENCES finaccounting.year_end_close (close_id);

ALTER TABLE finaccounting.year_end_close ENABLE ROW LEVEL SECURITY;
CREATE POLICY year_end_close_tenant_isolation ON finaccounting.year_end_close
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON finaccounting.year_end_close TO app_role;
