-- db-migrations/finaccounting/V12__unposted_events_and_paa_earning.sql
-- IFRS 17 I3a (posting engine).
--
-- 1. unposted_event: an event the posting rules could not post -- no rule for it (UNMAPPED), a journal the ledger
--    refused (REFUSED: a locked or closing period, a heading, a mode), or an unexpected failure (ERROR). The facts
--    the rules read are kept, so finance can post it once the rules are fixed (retry) or dismiss it with a reason.
--    Never a silent drop. One open row per (event, source); a resolved row stays as the record of what was done.
--
-- 2. paa_earning: what a PAA invoice has still to earn, one row per invoice and member (a credit-life file is one
--    row per borrower, earned over that borrower's own loan term; an instalment is one row over the period it pays
--    for). paa_earning_run records what each month's job earned from each row, so a re-run earns nothing twice.

CREATE TABLE finaccounting.unposted_event (
    unposted_event_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL,
    event_type         VARCHAR(60) NOT NULL,
    source_ref         VARCHAR(100) NOT NULL,
    policy_number      VARCHAR(20),
    period             VARCHAR(7) NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    facts              JSONB NOT NULL,
    reason             VARCHAR(10) NOT NULL CHECK (reason IN ('UNMAPPED','REFUSED','ERROR')),
    detail             VARCHAR(1000),
    rule_version       VARCHAR(40),
    attempts           INTEGER NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolution         VARCHAR(10) CHECK (resolution IN ('POSTED','DISMISSED')),
    resolution_reason  VARCHAR(500),
    resolved_by        VARCHAR(100),
    resolved_at        TIMESTAMPTZ,
    journal_entry_id   UUID,
    CHECK ((resolution IS NULL) = (resolved_at IS NULL)),
    CHECK (resolution IS DISTINCT FROM 'DISMISSED' OR resolution_reason IS NOT NULL),
    CHECK (resolution IS DISTINCT FROM 'POSTED' OR journal_entry_id IS NOT NULL)
);
CREATE UNIQUE INDEX ux_unposted_event_open
    ON finaccounting.unposted_event (tenant_id, event_type, source_ref) WHERE resolved_at IS NULL;
CREATE INDEX idx_unposted_event_period ON finaccounting.unposted_event (tenant_id, period) WHERE resolved_at IS NULL;

CREATE TABLE finaccounting.paa_earning (
    earning_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    policy_number  VARCHAR(20) NOT NULL,
    invoice_ref    VARCHAR(100) NOT NULL,
    member_ref     VARCHAR(100) NOT NULL DEFAULT '',
    group_key      VARCHAR(40) NOT NULL,
    currency       VARCHAR(3) NOT NULL,
    covers_from    DATE NOT NULL,
    covers_to      DATE NOT NULL,
    amount         NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    reduced        NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (reduced >= 0),
    earned         NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (earned >= 0),
    earned_through DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (covers_to >= covers_from),
    UNIQUE (tenant_id, invoice_ref, member_ref)
);
CREATE INDEX idx_paa_earning_due ON finaccounting.paa_earning (earned_through, covers_from);

CREATE TABLE finaccounting.paa_earning_run (
    tenant_id         UUID NOT NULL,
    earning_id        UUID NOT NULL REFERENCES finaccounting.paa_earning (earning_id),
    period            VARCHAR(7) NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    amount            NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    journal_entry_id  UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, earning_id, period)
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['unposted_event','paa_earning','paa_earning_run'] LOOP
        EXECUTE format('ALTER TABLE finaccounting.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON finaccounting.%I USING (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)',
                       t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON finaccounting.unposted_event, finaccounting.paa_earning TO app_role;
GRANT SELECT, INSERT ON finaccounting.paa_earning_run TO app_role;

-- The PAA earning job's selection, across tenants: each policy with a schedule row not yet earned through the given
-- month end. SECURITY DEFINER because the job runs under no tenant (accumulation.accounts_due_month_end's shape); the
-- earning itself runs under each policy's tenant.
CREATE OR REPLACE FUNCTION finaccounting.paa_policies_to_earn(p_through DATE)
RETURNS TABLE (tenant_id UUID, policy_number VARCHAR)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT DISTINCT e.tenant_id, e.policy_number FROM finaccounting.paa_earning e
     WHERE e.covers_from <= p_through
       AND e.earned < e.amount - e.reduced
       AND (e.earned_through IS NULL OR e.earned_through < p_through)
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION finaccounting.paa_policies_to_earn(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION finaccounting.paa_policies_to_earn(DATE) TO app_role;
