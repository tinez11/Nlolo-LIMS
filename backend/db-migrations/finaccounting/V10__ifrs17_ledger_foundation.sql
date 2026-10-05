-- db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql
-- IFRS 17 I1 (spec 2026-10-05 §5): the posting guide's chart replaces the placeholder chart, the development ledger
-- starts clean (decision D2), every journal gains the guide's header and line dimensions, accounting periods and the
-- accounting policy register get their tables, and the ledger's invariants are enforced here rather than only in Java.
-- The chart itself is seeded per tenant by ChartOfAccountSeeder from finaccounting/ifrs17-chart.csv.

-- 1. Clean start: ledger rows only. No other schema's data is touched.
TRUNCATE finaccounting.gl_posting, finaccounting.journal_entry;
DELETE FROM finaccounting.chart_of_account;
-- M1's measurement ledgers were never used; the I5 engine results replace them (spec §5.6, I1 R6).
DROP TABLE IF EXISTS finaccounting.csm_ledger, finaccounting.lrc_ledger, finaccounting.lic_ledger;

-- 2. Chart: the CLEARING class and each account's posting mode (guide 2.3).
ALTER TABLE finaccounting.chart_of_account DROP CONSTRAINT chart_of_account_account_type_check;
ALTER TABLE finaccounting.chart_of_account ADD CONSTRAINT chart_of_account_account_type_check
    CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE','CLEARING'));
ALTER TABLE finaccounting.chart_of_account ADD COLUMN posting_mode VARCHAR(4) NOT NULL DEFAULT 'MAN'
    CHECK (posting_mode IN ('AUTO','MAN','BOTH'));

-- 3. Journal header.
ALTER TABLE finaccounting.journal_entry
    ADD COLUMN source_type VARCHAR(10) NOT NULL DEFAULT 'EVENT'
        CHECK (source_type IN ('EVENT','SYSTEM','ENGINE_RUN','MANUAL')),
    ADD COLUMN preparer VARCHAR(100),
    ADD COLUMN approver VARCHAR(100),
    ADD COLUMN reason VARCHAR(500),
    ADD COLUMN reason_code VARCHAR(40),
    ADD COLUMN document_refs TEXT,
    ADD COLUMN reverses_journal_id UUID REFERENCES finaccounting.journal_entry(journal_entry_id),
    ADD COLUMN auto_reverse_on DATE,
    ADD COLUMN policy_register_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rule_version VARCHAR(40),
    ADD COLUMN engine_run_id UUID,
    -- The transaction that wrote the journal: its lines may only be written by that same transaction (I1 R5).
    -- pg_current_xact_id() is the top-level transaction's id even inside a savepoint, unlike a row's xmin.
    ADD COLUMN created_xid xid8 NOT NULL DEFAULT pg_current_xact_id();
ALTER TABLE finaccounting.journal_entry ADD CONSTRAINT journal_manual_has_people
    CHECK (source_type <> 'MANUAL' OR (preparer IS NOT NULL AND approver IS NOT NULL AND approver <> preparer
                                       AND reason IS NOT NULL));

-- 4. Journal line dimensions (guide 2.2). Nullable: classification (I2) and the rules engine (I3) fill them, and a
--    line with no policy behind it has no group.
ALTER TABLE finaccounting.gl_posting
    ADD COLUMN ifrs17_group VARCHAR(40),
    ADD COLUMN measurement_model VARCHAR(5) CHECK (measurement_model IN ('GMM','VFA','PAA','IFRS9')),
    ADD COLUMN movement_type VARCHAR(20),
    ADD COLUMN product_id UUID,
    ADD COLUMN portfolio VARCHAR(10),
    ADD COLUMN channel VARCHAR(15),
    ADD COLUMN branch VARCHAR(10),
    ADD COLUMN fund VARCHAR(30),
    ADD COLUMN reference_type VARCHAR(20),
    ADD COLUMN reference VARCHAR(100);

-- 5. Accounting periods (spec §5.4). A period with no row is OPEN.
CREATE TABLE finaccounting.accounting_period (
    tenant_id              UUID NOT NULL,
    period                 VARCHAR(7) NOT NULL CHECK (period ~ '^\d{4}-(0[1-9]|1[0-2])$'),
    status                 VARCHAR(7) NOT NULL CHECK (status IN ('OPEN','CLOSING','LOCKED')),
    closing_started_by     VARCHAR(100),
    closing_started_at     TIMESTAMPTZ,
    locked_by              VARCHAR(100),
    locked_at              TIMESTAMPTZ,
    reopen_requested_by    VARCHAR(100),
    reopen_requested_at    TIMESTAMPTZ,
    reopen_reason          VARCHAR(500),
    reopened_by            VARCHAR(100),
    reopened_at            TIMESTAMPTZ,
    version                BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, period),
    CHECK (reopened_by IS NULL OR reopened_by <> reopen_requested_by)
);

-- 6. The accounting policy register (spec §3, decision D7). An election is decided once and never edited (trigger
--    below); a change is a new, dated election.
CREATE TABLE finaccounting.accounting_policy_election (
    election_id         UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    election_key        VARCHAR(40) NOT NULL,
    scope               VARCHAR(20) NOT NULL DEFAULT '*',
    election_value      VARCHAR(100) NOT NULL,
    effective_from      DATE NOT NULL,
    status              VARCHAR(9) NOT NULL CHECK (status IN ('PROPOSED','APPROVED','REJECTED')),
    rationale           VARCHAR(1000),
    sign_off_ref        VARCHAR(200),
    decision_reason     VARCHAR(500),
    proposed_by         VARCHAR(100) NOT NULL,
    proposed_at         TIMESTAMPTZ NOT NULL,
    decided_by          VARCHAR(100),
    decided_at          TIMESTAMPTZ,
    register_version    INTEGER,
    version             BIGINT NOT NULL DEFAULT 0,
    CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CHECK (status <> 'APPROVED' OR (sign_off_ref IS NOT NULL AND register_version IS NOT NULL))
);
CREATE INDEX idx_policy_election_lookup
    ON finaccounting.accounting_policy_election (tenant_id, election_key, scope, effective_from);
CREATE UNIQUE INDEX ux_policy_election_version
    ON finaccounting.accounting_policy_election (tenant_id, register_version) WHERE register_version IS NOT NULL;
CREATE UNIQUE INDEX ux_policy_election_one_approved
    ON finaccounting.accounting_policy_election (tenant_id, election_key, scope, effective_from)
    WHERE status = 'APPROVED';

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['accounting_period','accounting_policy_election'] LOOP
        EXECUTE format('ALTER TABLE finaccounting.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON finaccounting.%I USING (tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)',
                       t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON finaccounting.accounting_period, finaccounting.accounting_policy_election TO app_role;

-- 7. Guards. Every message starts with a stable prefix the Java side maps to a 409.

-- 7a. Posted journals and lines are never changed. app_role already lacks UPDATE/DELETE on both; this binds every
--     role, the owner included. (TRUNCATE fires no row trigger, which is why section 1 can clear them.)
CREATE OR REPLACE FUNCTION finaccounting.refuse_ledger_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'LEDGER_IMMUTABLE: % rows are never updated or deleted; post a reversal', TG_TABLE_NAME;
END $$;
CREATE TRIGGER trg_journal_entry_immutable BEFORE UPDATE OR DELETE ON finaccounting.journal_entry
    FOR EACH ROW EXECUTE FUNCTION finaccounting.refuse_ledger_change();
CREATE TRIGGER trg_gl_posting_immutable BEFORE UPDATE OR DELETE ON finaccounting.gl_posting
    FOR EACH ROW EXECUTE FUNCTION finaccounting.refuse_ledger_change();

-- 7b. A line joins only a journal written in this same transaction (I1 R5), on a posting account whose mode accepts
--     the journal's source (spec §5.3), in a period that is not locked -- nor closing, for an event.
CREATE OR REPLACE FUNCTION finaccounting.guard_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    j            finaccounting.journal_entry%ROWTYPE;
    acct_mode    text;
    acct_allowed boolean;
    period_state text;
BEGIN
    SELECT * INTO j FROM finaccounting.journal_entry WHERE journal_entry_id = NEW.journal_entry_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'LEDGER_NO_JOURNAL: line for unknown journal %', NEW.journal_entry_id;
    END IF;
    IF j.created_xid <> pg_current_xact_id() THEN
        RAISE EXCEPTION 'LEDGER_SEALED: journal % was posted in an earlier transaction; lines cannot be added',
            NEW.journal_entry_id;
    END IF;
    SELECT posting_mode, posting_allowed INTO acct_mode, acct_allowed
      FROM finaccounting.chart_of_account WHERE tenant_id = NEW.tenant_id AND account_code = NEW.account_code;
    IF NOT acct_allowed THEN
        RAISE EXCEPTION 'LEDGER_HEADING: % is a heading and takes no postings', NEW.account_code;
    END IF;
    IF (acct_mode = 'AUTO' AND j.source_type = 'MANUAL')
       OR (acct_mode = 'MAN' AND j.source_type = 'EVENT') THEN
        RAISE EXCEPTION 'LEDGER_MODE: account % is % and refuses a % journal', NEW.account_code, acct_mode, j.source_type;
    END IF;
    IF acct_mode = 'BOTH' AND j.source_type = 'MANUAL' AND j.reason_code IS NULL THEN
        RAISE EXCEPTION 'LEDGER_MODE: a manual line on BOTH account % needs a reason code', NEW.account_code;
    END IF;
    SELECT status INTO period_state FROM finaccounting.accounting_period
     WHERE tenant_id = NEW.tenant_id AND period = j.period;
    IF period_state = 'LOCKED' THEN
        RAISE EXCEPTION 'LEDGER_PERIOD_LOCKED: period % is locked', j.period;
    END IF;
    IF period_state = 'CLOSING' AND j.source_type = 'EVENT' THEN
        RAISE EXCEPTION 'LEDGER_PERIOD_CLOSING: period % is closing and takes no event postings', j.period;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_gl_posting_guard BEFORE INSERT ON finaccounting.gl_posting
    FOR EACH ROW EXECUTE FUNCTION finaccounting.guard_posting();

-- 7c. Every journal balances, checked at commit (deferred), with at least one line on each side.
CREATE OR REPLACE FUNCTION finaccounting.check_journal_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE dr numeric; cr numeric; n integer;
BEGIN
    SELECT coalesce(sum(amount) FILTER (WHERE direction = 'DR'), 0),
           coalesce(sum(amount) FILTER (WHERE direction = 'CR'), 0), count(*)
      INTO dr, cr, n
      FROM finaccounting.gl_posting WHERE journal_entry_id = NEW.journal_entry_id;
    IF n = 0 OR dr <> cr OR dr = 0 THEN
        RAISE EXCEPTION 'LEDGER_UNBALANCED: journal % has DR % and CR % over % lines', NEW.journal_entry_id, dr, cr, n;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_journal_entry_balanced AFTER INSERT ON finaccounting.journal_entry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION finaccounting.check_journal_balanced();

-- 7d. An election is decided once (PROPOSED -> APPROVED | REJECTED) and its terms never change.
CREATE OR REPLACE FUNCTION finaccounting.guard_policy_election() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status <> 'PROPOSED'
       OR NEW.election_key <> OLD.election_key OR NEW.scope <> OLD.scope
       OR NEW.election_value <> OLD.election_value OR NEW.effective_from <> OLD.effective_from
       OR NEW.proposed_by <> OLD.proposed_by THEN
        RAISE EXCEPTION 'POLICY_ELECTION_IMMUTABLE: an election is decided once and never edited; propose a new one';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_policy_election_guard BEFORE UPDATE OR DELETE ON finaccounting.accounting_policy_election
    FOR EACH ROW EXECUTE FUNCTION finaccounting.guard_policy_election();
