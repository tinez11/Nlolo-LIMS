-- db-migrations/finaccounting/V15__engine_period_cycle.sql
-- IFRS 17 I5a: the engine period cycle -- the extract the IFRS 17 engine is sent (month-end step 6), its results
-- loaded through 9160 with maker-checker (step 7), and the ledger reconciled to it. Sections:
--   A. reinsurance_group_ref -- which reinsurance-held group (RI-<treaty>-<year>) each reinsurance posting belongs to.
--   B. policy_snapshot -- what the extract's policies sheet needs of each policy, kept from policy's events
--      (finaccounting cannot read policy::api: finaccounting -> policy -> distribution -> finaccounting is a cycle).

-- A. Reinsurance contracts held are their own groups (IFRS 17 para 61), one per treaty and year. Posted ledger lines
--    are immutable, so the group is not written onto them: this maps the posting's source record (a bordereau, a
--    recovery, a statement) to its group, fed by the reinsurance events from I5a on and backfilled below.
CREATE TABLE finaccounting.reinsurance_group_ref (
    tenant_id       UUID NOT NULL,
    reference_type  VARCHAR(20) NOT NULL CHECK (reference_type IN ('BORDEREAU','RECOVERY','STATEMENT')),
    reference       VARCHAR(100) NOT NULL,
    group_key       VARCHAR(40) NOT NULL CHECK (group_key LIKE 'RI-%'),
    PRIMARY KEY (tenant_id, reference_type, reference)
);
CREATE INDEX idx_reinsurance_group_ref_group ON finaccounting.reinsurance_group_ref (tenant_id, group_key);
ALTER TABLE finaccounting.reinsurance_group_ref ENABLE ROW LEVEL SECURITY;
CREATE POLICY reinsurance_group_ref_tenant_isolation ON finaccounting.reinsurance_group_ref
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT ON finaccounting.reinsurance_group_ref TO app_role;

DO $$
BEGIN
    IF to_regclass('reinsurance.bordereau') IS NULL THEN
        RETURN;   -- a module test without reinsurance: nothing to map
    END IF;
    INSERT INTO finaccounting.reinsurance_group_ref (tenant_id, reference_type, reference, group_key)
    SELECT b.tenant_id, 'BORDEREAU', b.bordereau_id::text,
           'RI-' || upper(substr(t.treaty_id::text, 1, 8)) || '-' || extract(year FROM t.effective_from)::int
      FROM reinsurance.bordereau b JOIN reinsurance.reinsurance_treaty t ON t.treaty_id = b.treaty_id
    ON CONFLICT DO NOTHING;
    INSERT INTO finaccounting.reinsurance_group_ref (tenant_id, reference_type, reference, group_key)
    SELECT r.tenant_id, 'RECOVERY', r.recovery_id::text,
           'RI-' || upper(substr(t.treaty_id::text, 1, 8)) || '-' || extract(year FROM t.effective_from)::int
      FROM reinsurance.claim_recovery r JOIN reinsurance.reinsurance_treaty t ON t.treaty_id = r.treaty_id
    ON CONFLICT DO NOTHING;
    IF to_regclass('reinsurance.statement') IS NOT NULL THEN
        INSERT INTO finaccounting.reinsurance_group_ref (tenant_id, reference_type, reference, group_key)
        SELECT s.tenant_id, 'STATEMENT', s.statement_id::text,
               'RI-' || upper(substr(t.treaty_id::text, 1, 8)) || '-' || extract(year FROM t.effective_from)::int
          FROM reinsurance.statement s JOIN reinsurance.reinsurance_treaty t ON t.treaty_id = s.treaty_id
        ON CONFLICT DO NOTHING;
    END IF;
END;
$$;

-- B. What the engine's policies sheet needs of a policy: its sum assured, premium and frequency from activation, its
--    status as the lifecycle events move it. Fed by EnginePolicySnapshots; backfilled from policy's own record.
CREATE TABLE finaccounting.policy_snapshot (
    tenant_id          UUID NOT NULL,
    policy_number      VARCHAR(20) NOT NULL,
    issue_date         DATE,
    sum_assured        NUMERIC(19,2),
    premium            NUMERIC(19,2),
    premium_frequency  VARCHAR(20),
    currency           CHAR(3),
    status             VARCHAR(30) NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, policy_number)
);
ALTER TABLE finaccounting.policy_snapshot ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_snapshot_tenant_isolation ON finaccounting.policy_snapshot
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON finaccounting.policy_snapshot TO app_role;

DO $$
BEGIN
    IF to_regclass('policy.policy') IS NULL THEN
        RETURN;
    END IF;
    INSERT INTO finaccounting.policy_snapshot (tenant_id, policy_number, issue_date, sum_assured, premium,
                                               premium_frequency, currency, status)
    SELECT p.tenant_id, p.policy_number, p.issue_date, p.sum_assured_amount, p.premium_amount, p.premium_frequency,
           p.premium_currency, p.status
      FROM policy.policy p
     WHERE p.status <> 'PROPOSED'
    ON CONFLICT DO NOTHING;
END;
$$;

-- C. The extracts the engine is sent (month-end step 6), numbered per period and kept: the sheets themselves (JSONB, so
--    a download later reproduces exactly what was sent, whatever has posted since), the groups they name (an engine
--    run may report only on those), and the workbook stored in the document store.
CREATE TABLE finaccounting.engine_extract (
    extract_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    period          VARCHAR(7) NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    number          INTEGER NOT NULL CHECK (number >= 1),
    groups          TEXT[] NOT NULL,
    cash_flows      JSONB NOT NULL,
    balances        JSONB NOT NULL,
    policies        JSONB NOT NULL,
    document_ref    VARCHAR(255),
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, period, number)
);
ALTER TABLE finaccounting.engine_extract ENABLE ROW LEVEL SECURITY;
CREATE POLICY engine_extract_tenant_isolation ON finaccounting.engine_extract
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON finaccounting.engine_extract TO app_role;

-- D. The engine's runs (step 7): uploaded, validated or rejected (with every error), approved by a FINANCE_APPROVER who
--    did not upload it -- with the appointed actuary's sign-off reference and report -- and posted as ENGINE_RUN
--    journals through 9160. A later run of the same period replaces a posted one: its approval reverses the old run's
--    journals in full first. One posted run per period.
CREATE TABLE finaccounting.engine_run (
    run_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    period                VARCHAR(7),
    extract_number        INTEGER,
    engine_reference      VARCHAR(100),
    engine_name           VARCHAR(100),
    measurement_date      VARCHAR(20),
    status                VARCHAR(10) NOT NULL CHECK (status IN ('VALIDATED','REJECTED','POSTED','REPLACED')),
    errors                TEXT[] NOT NULL DEFAULT '{}',
    results_document_ref  VARCHAR(255),
    file_name             VARCHAR(255),
    uploaded_by           VARCHAR(100) NOT NULL,
    uploaded_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by            VARCHAR(100),
    decided_at            TIMESTAMPTZ,
    decision_reason       VARCHAR(500),
    signoff_reference     VARCHAR(200),
    report_document_ref   VARCHAR(255),
    replaces_run_id       UUID REFERENCES finaccounting.engine_run (run_id),
    version               BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT engine_run_decider_not_uploader CHECK (decided_by IS NULL OR decided_by <> uploaded_by),
    CONSTRAINT engine_run_posted_has_signoff CHECK (status NOT IN ('POSTED','REPLACED')
        OR (signoff_reference IS NOT NULL AND report_document_ref IS NOT NULL))
);
CREATE UNIQUE INDEX ux_engine_run_reference ON finaccounting.engine_run (tenant_id, engine_reference)
    WHERE status <> 'REJECTED';
CREATE UNIQUE INDEX ux_engine_run_one_posted ON finaccounting.engine_run (tenant_id, period) WHERE status = 'POSTED';
CREATE INDEX idx_engine_run_period ON finaccounting.engine_run (tenant_id, period, uploaded_at);

CREATE TABLE finaccounting.engine_run_line (
    run_id        UUID NOT NULL REFERENCES finaccounting.engine_run (run_id),
    tenant_id     UUID NOT NULL,
    row_no        INTEGER NOT NULL,
    group_key     VARCHAR(40) NOT NULL,
    entry         VARCHAR(5) NOT NULL,
    account_code  VARCHAR(20) NOT NULL,
    direction     VARCHAR(2) NOT NULL CHECK (direction IN ('DR','CR')),
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    movement      VARCHAR(20),
    note          VARCHAR(300),
    PRIMARY KEY (run_id, row_no)
);

CREATE TABLE finaccounting.engine_run_closing (
    run_id      UUID NOT NULL REFERENCES finaccounting.engine_run (run_id),
    tenant_id   UUID NOT NULL,
    group_key   VARCHAR(40) NOT NULL,
    lrc         NUMERIC(19,2),
    lic         NUMERIC(19,2),
    csm         NUMERIC(19,2),
    arc         NUMERIC(19,2),
    aic         NUMERIC(19,2),
    ri_csm      NUMERIC(19,2),
    PRIMARY KEY (run_id, group_key)
);

-- E. The reconciliation of a posted run (user answer Q4): each group's ledger figure against the engine's closing one.
--    TZS 1.00 or less is rounding (AGREED); more is an EXCEPTION that blocks the period lock until a finance officer
--    explains it and a FINANCE_APPROVER who did not explain it accepts it -- or a replacement run makes them agree.
CREATE TABLE finaccounting.engine_reconciliation (
    run_id        UUID NOT NULL REFERENCES finaccounting.engine_run (run_id),
    tenant_id     UUID NOT NULL,
    group_key     VARCHAR(40) NOT NULL,
    figure        VARCHAR(10) NOT NULL CHECK (figure IN ('LRC','LIC','CSM','ARC','AIC','RI_CSM')),
    ledger        NUMERIC(19,2) NOT NULL,
    engine        NUMERIC(19,2) NOT NULL,
    difference    NUMERIC(19,2) NOT NULL,
    status        VARCHAR(10) NOT NULL CHECK (status IN ('AGREED','EXCEPTION','EXPLAINED','ACCEPTED')),
    explanation   VARCHAR(1000),
    explained_by  VARCHAR(100),
    explained_at  TIMESTAMPTZ,
    accepted_by   VARCHAR(100),
    accepted_at   TIMESTAMPTZ,
    PRIMARY KEY (run_id, group_key, figure),
    CONSTRAINT engine_reconciliation_accepter_not_explainer CHECK (accepted_by IS NULL OR accepted_by <> explained_by)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['engine_run','engine_run_line','engine_run_closing','engine_reconciliation'] LOOP
        EXECUTE format('ALTER TABLE finaccounting.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON finaccounting.%I USING (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)',
                       t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON finaccounting.engine_run, finaccounting.engine_reconciliation TO app_role;
GRANT SELECT, INSERT ON finaccounting.engine_run_line, finaccounting.engine_run_closing TO app_role;
