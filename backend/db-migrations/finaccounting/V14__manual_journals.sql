-- db-migrations/finaccounting/V14__manual_journals.sql
-- IFRS 17 I4 (spec §8, guide 2.3 and Part 4): manual journals. A draft is prepared by one person and approved by
-- another holding FINANCE_APPROVER; only then is a journal_entry written (posted journals are immutable, V10). The
-- draft keeps the whole story: who prepared, submitted, decided, why, the documents, and the journal it became.
--
-- 1. manual_journal / manual_journal_line: the draft and its lines. DRAFT -> SUBMITTED -> APPROVED | REJECTED; a
--    SUBMITTED draft may be withdrawn to DRAFT by its preparer. A reversal is a draft with reverses_journal_id set,
--    approved like any other; an original may be reversed once.
-- 2. journal_template: a tenant's saved recurring template (depreciation, lease interest, payroll). The guide's own
--    library ships as a file (finaccounting/manual-journal-templates.yaml), not here.
-- 3. auto_reversal: an approved journal with a reverse-on date is reversed by the platform on that date as a SYSTEM
--    journal, without a second approval (user answer Q5); this records that it was, so it happens once.

CREATE TABLE finaccounting.manual_journal (
    manual_journal_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    status              VARCHAR(10) NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED')),
    period              VARCHAR(7) NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    currency            VARCHAR(3) NOT NULL DEFAULT 'TZS',
    title               VARCHAR(200) NOT NULL,
    reason              VARCHAR(500),
    reason_code         VARCHAR(40),
    template_id         VARCHAR(60),
    reverses_journal_id UUID,
    auto_reverse_on     DATE,
    document_refs       TEXT[] NOT NULL DEFAULT '{}',
    preparer            VARCHAR(100) NOT NULL,
    prepared_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at        TIMESTAMPTZ,
    decided_by          VARCHAR(100),
    decided_at          TIMESTAMPTZ,
    decision_reason     VARCHAR(500),
    journal_entry_id    UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    CHECK (decided_by IS NULL OR decided_by <> preparer),
    CHECK (status <> 'APPROVED' OR journal_entry_id IS NOT NULL),
    CHECK (status <> 'REJECTED' OR decision_reason IS NOT NULL)
);
CREATE INDEX idx_manual_journal_status ON finaccounting.manual_journal (tenant_id, status, prepared_at);
-- An original is reversed once: one live (not rejected) reversal draft per journal.
CREATE UNIQUE INDEX ux_manual_journal_one_reversal ON finaccounting.manual_journal (tenant_id, reverses_journal_id)
    WHERE reverses_journal_id IS NOT NULL AND status <> 'REJECTED';

CREATE TABLE finaccounting.manual_journal_line (
    manual_journal_id  UUID NOT NULL REFERENCES finaccounting.manual_journal (manual_journal_id) ON DELETE CASCADE,
    line_no            INTEGER NOT NULL,
    tenant_id          UUID NOT NULL,
    account_code       VARCHAR(4) NOT NULL,
    direction          VARCHAR(2) NOT NULL CHECK (direction IN ('DR','CR')),
    amount             NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    description        VARCHAR(300),
    branch             VARCHAR(10),
    fund               VARCHAR(30),
    reference_type     VARCHAR(20),
    reference          VARCHAR(100),
    PRIMARY KEY (manual_journal_id, line_no)
);

CREATE TABLE finaccounting.journal_template (
    template_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    name          VARCHAR(200) NOT NULL,
    description   VARCHAR(500),
    lines         JSONB NOT NULL,
    reason_code   VARCHAR(40),
    created_by    VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at    TIMESTAMPTZ,
    UNIQUE (tenant_id, name)
);

CREATE TABLE finaccounting.auto_reversal (
    tenant_id            UUID NOT NULL,
    original_journal_id  UUID NOT NULL,
    reverse_on           DATE NOT NULL,
    reversal_journal_id  UUID,
    reversed_at          TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, original_journal_id)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['manual_journal','manual_journal_line','journal_template','auto_reversal'] LOOP
        EXECUTE format('ALTER TABLE finaccounting.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON finaccounting.%I USING (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)',
                       t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON finaccounting.manual_journal, finaccounting.journal_template,
    finaccounting.auto_reversal TO app_role;
GRANT SELECT, INSERT, DELETE ON finaccounting.manual_journal_line TO app_role;

-- The auto-reversal drain's selection, across tenants (the job runs under none).
CREATE OR REPLACE FUNCTION finaccounting.auto_reversals_due(p_on DATE)
RETURNS TABLE (tenant_id UUID, original_journal_id UUID, reverse_on DATE)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT a.tenant_id, a.original_journal_id, a.reverse_on FROM finaccounting.auto_reversal a
     WHERE a.reversed_at IS NULL AND a.reverse_on <= p_on
     ORDER BY a.reverse_on LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION finaccounting.auto_reversals_due(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION finaccounting.auto_reversals_due(DATE) TO app_role;
