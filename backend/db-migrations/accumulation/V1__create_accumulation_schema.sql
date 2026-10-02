-- db-migrations/accumulation/V1__create_accumulation_schema.sql
-- Product step 3: the accumulation engine -- the guide's "bucket" (S21.4). It owns an account's
-- balance and EVERY transaction that changes it. No foreign key into another module's tables: a
-- policy number is carried as a value, this platform's cross-module rule.
--
-- Not partitioned, for the reason benefitpayout gives: a partitioned table must be registered with
-- pg_partman and listed in ops.platform_readiness(), and at these volumes a plain table is right.
CREATE SCHEMA IF NOT EXISTS accumulation;
GRANT USAGE ON SCHEMA accumulation TO app_role;

-- One per ACCOUNT-basis policy. balance and last_seq are the RUNNING HEAD of the ledger, kept in
-- the same transaction as each posting so a new entry knows its seq and its balance_after without
-- summing history. They are a copy, not the truth: the insert trigger below refuses any entry whose
-- balance_after does not follow from the entry before it, so the head cannot drift from the ledger
-- without the next posting failing.
CREATE TABLE accumulation.account (
    policy_number         VARCHAR(20) PRIMARY KEY,
    tenant_id             UUID NOT NULL,
    product_id            UUID NOT NULL,
    product_version_id    UUID NOT NULL,
    policyholder_party_id UUID NOT NULL,
    currency              CHAR(3) NOT NULL DEFAULT 'TZS',
    opened_on             DATE NOT NULL,
    status                VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    closed_reason         VARCHAR(30),
    closed_on             DATE,
    last_seq              INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    balance               NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    -- The last month whose interest and fee are posted; the month-end run catches up from here.
    last_month_end        DATE,
    version               BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT account_closed_shape CHECK ((status = 'CLOSED') = (closed_on IS NOT NULL))
);

-- The atomic unit: one header per SOURCE, its entries written in the same transaction.
-- ux_posting_source IS the exactly-once guarantee -- not a check-then-insert. Two concurrent
-- deliveries of the same invoice cannot both commit: the loser hits this index and rolls back whole.
CREATE TABLE accumulation.posting (
    posting_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    source_type   VARCHAR(30) NOT NULL,
    source_ref    VARCHAR(100) NOT NULL,
    posted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(100) NOT NULL,
    approved_by   VARCHAR(100)
);
CREATE UNIQUE INDEX ux_posting_source ON accumulation.posting (tenant_id, source_type, source_ref);

CREATE TABLE accumulation.ledger_entry (
    entry_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    posting_id        UUID NOT NULL REFERENCES accumulation.posting(posting_id),
    policy_number     VARCHAR(20) NOT NULL,
    seq               INTEGER NOT NULL CHECK (seq >= 1),
    entry_type        VARCHAR(20) NOT NULL CHECK (entry_type IN ('CONTRIBUTION','TOP_UP','TRANSFER_IN',
                          'ALLOCATION_CHARGE','POLICY_FEE','INTEREST','WITHDRAWAL','SURRENDER','MATURITY',
                          'DEATH_CLAIM','FREE_LOOK_REFUND','ADJUSTMENT','REVERSAL')),
    amount            NUMERIC(19,2) NOT NULL,
    balance_after     NUMERIC(19,2) NOT NULL CHECK (balance_after >= 0),
    effective_date    DATE NOT NULL,
    posted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    reverses_entry_id UUID REFERENCES accumulation.ledger_entry(entry_id),
    reason            VARCHAR(500),
    created_by        VARCHAR(100) NOT NULL,
    approved_by       VARCHAR(100),
    -- A reversal says what it reverses; nothing else may.
    CONSTRAINT ledger_entry_reversal_shape CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL)),
    -- A correction a person typed needs a second person.
    CONSTRAINT ledger_entry_adjustment_approved CHECK (entry_type <> 'ADJUSTMENT'
        OR (approved_by IS NOT NULL AND approved_by <> created_by))
);
CREATE UNIQUE INDEX ux_ledger_entry_seq ON accumulation.ledger_entry (policy_number, seq);
-- An entry is reversed at most once.
CREATE UNIQUE INDEX ux_ledger_entry_reversed_once ON accumulation.ledger_entry (reverses_entry_id)
    WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX ix_ledger_entry_policy_date ON accumulation.ledger_entry (policy_number, effective_date);

-- IMMUTABILITY, enforced twice. The grants below give app_role SELECT and INSERT only on these two
-- tables -- the policy/V2 endorsement precedent. But grants do not bind the table OWNER, which every
-- migration and every integration test connects as, so this trigger refuses UPDATE and DELETE for
-- every role. A correction is a REVERSAL or an ADJUSTMENT, never an edit.
CREATE OR REPLACE FUNCTION accumulation.refuse_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'accumulation.% is append-only: correct it with a reversing or adjustment entry, never an %',
        TG_TABLE_NAME, TG_OP;
END $$;
CREATE TRIGGER ledger_entry_append_only BEFORE UPDATE OR DELETE ON accumulation.ledger_entry
    FOR EACH ROW EXECUTE FUNCTION accumulation.refuse_mutation();
CREATE TRIGGER posting_append_only BEFORE UPDATE OR DELETE ON accumulation.posting
    FOR EACH ROW EXECUTE FUNCTION accumulation.refuse_mutation();

-- balance_after cannot drift: each entry must follow from the one before it, and seq must be
-- contiguous. A database rule rather than a Java one, so no code path -- including a hand-written
-- INSERT -- can write a ledger that does not add up.
CREATE OR REPLACE FUNCTION accumulation.check_entry_follows() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous NUMERIC(19,2);
BEGIN
    IF NEW.seq = 1 THEN
        previous := 0;
    ELSE
        SELECT balance_after INTO previous FROM accumulation.ledger_entry
         WHERE policy_number = NEW.policy_number AND seq = NEW.seq - 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'ledger entry % for policy % has no entry % before it', NEW.seq, NEW.policy_number, NEW.seq - 1;
        END IF;
    END IF;
    IF NEW.balance_after <> previous + NEW.amount THEN
        RAISE EXCEPTION 'ledger entry % for policy %: balance_after % is not % + %',
            NEW.seq, NEW.policy_number, NEW.balance_after, previous, NEW.amount;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER ledger_entry_follows BEFORE INSERT ON accumulation.ledger_entry
    FOR EACH ROW EXECUTE FUNCTION accumulation.check_entry_follows();

-- A declared rate on top of each version's guarantee (decision Q2). Per PRODUCT: every account on
-- the product earns max(declared, its own version's guarantee).
CREATE TABLE accumulation.rate_declaration (
    declaration_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL,
    product_id     UUID NOT NULL,
    rate_percent   NUMERIC(7,4) NOT NULL CHECK (rate_percent BETWEEN 0 AND 100),
    effective_from DATE NOT NULL,
    status         VARCHAR(10) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','APPROVED','WITHDRAWN')),
    proposed_by    VARCHAR(100) NOT NULL,
    proposed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by    VARCHAR(100),
    approved_at    TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT rate_declaration_two_person CHECK (approved_by IS NULL OR approved_by <> proposed_by),
    CONSTRAINT rate_declaration_approved_shape CHECK ((status = 'APPROVED') = (approved_by IS NOT NULL))
);
-- One approved rate per product per day it takes effect.
CREATE UNIQUE INDEX ux_rate_declaration_effective ON accumulation.rate_declaration (product_id, effective_from)
    WHERE status = 'APPROVED';

CREATE TABLE accumulation.withdrawal_request (
    withdrawal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    payee_ref     VARCHAR(200) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','APPROVED','PAID','FAILED')),
    requested_by  VARCHAR(100) NOT NULL,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_by   VARCHAR(100),
    approved_at   TIMESTAMPTZ,
    disbursement_id UUID,
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT withdrawal_two_person CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
-- One withdrawal in flight per account, the shape ux_surrender_request_live uses.
CREATE UNIQUE INDEX ux_withdrawal_live ON accumulation.withdrawal_request (policy_number)
    WHERE status IN ('REQUESTED','APPROVED');

CREATE TABLE accumulation.top_up_request (
    top_up_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    payer_ref     VARCHAR(200) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','COLLECTED','FAILED')),
    requested_by  VARCHAR(100) NOT NULL,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE accumulation.transfer_in (
    transfer_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency      CHAR(3) NOT NULL DEFAULT 'TZS',
    source_scheme VARCHAR(200) NOT NULL,
    document_ref  VARCHAR(200),
    recorded_by   VARCHAR(100) NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accumulation.statement (
    statement_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    period_from   DATE NOT NULL,
    period_to     DATE NOT NULL CHECK (period_to >= period_from),
    last_seq      INTEGER NOT NULL CHECK (last_seq >= 0),
    document_ref  VARCHAR(200) NOT NULL,
    generated_by  VARCHAR(100) NOT NULL,
    generated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- A statement is filed once per what it was built from, so regenerating with nothing new posted is
-- provably the same statement, and a later correction makes a new one rather than editing this.
CREATE UNIQUE INDEX ux_statement_identity ON accumulation.statement (policy_number, period_from, period_to, last_seq);

-- The one way a PERSON corrects a ledger: a signed amount one person proposes and a second
-- approves, posted only on approval as an ADJUSTMENT entry. (A REVERSAL is the system undoing its
-- own entry.) ledger_entry_adjustment_approved is the database's half of the same rule.
CREATE TABLE accumulation.adjustment_request (
    adjustment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    policy_number VARCHAR(20) NOT NULL,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    reason        VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    status        VARCHAR(10) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','APPROVED','REJECTED')),
    proposed_by   VARCHAR(100) NOT NULL,
    proposed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by    VARCHAR(100),
    decided_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT adjustment_two_person CHECK (decided_by IS NULL OR decided_by <> proposed_by)
);

-- Cross-tenant selectors for the drains: ids only, never a balance (benefitpayout's shape).
CREATE OR REPLACE FUNCTION accumulation.accounts_due_month_end()
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT a.policy_number, a.tenant_id FROM accumulation.account a
     WHERE a.status = 'OPEN'
       AND coalesce(a.last_month_end, (date_trunc('month', a.opened_on) - interval '1 day')::date)
           < (date_trunc('month', current_date) - interval '1 day')::date
     LIMIT 500;
$$;
CREATE OR REPLACE FUNCTION accumulation.accounts_due_annual_statement()
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT a.policy_number, a.tenant_id FROM accumulation.account a
     WHERE a.status = 'OPEN'
       AND NOT EXISTS (SELECT 1 FROM accumulation.statement s
                        WHERE s.policy_number = a.policy_number
                          AND s.period_to = (date_trunc('year', current_date) - interval '1 day')::date)
       AND a.opened_on <= (date_trunc('year', current_date) - interval '1 day')::date
     LIMIT 500;
$$;
REVOKE EXECUTE ON FUNCTION accumulation.accounts_due_month_end() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION accumulation.accounts_due_annual_statement() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION accumulation.accounts_due_month_end() TO app_role;
GRANT EXECUTE ON FUNCTION accumulation.accounts_due_annual_statement() TO app_role;

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['account','posting','ledger_entry','rate_declaration','withdrawal_request',
                             'top_up_request','transfer_in','statement','adjustment_request'] LOOP
        EXECUTE format('ALTER TABLE accumulation.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON accumulation.%I USING (tenant_id = '
            'NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid)', t || '_tenant_isolation', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON accumulation.account, accumulation.rate_declaration,
    accumulation.withdrawal_request, accumulation.top_up_request, accumulation.statement,
    accumulation.adjustment_request TO app_role;
-- The ledger itself: read and append, nothing else.
GRANT SELECT, INSERT ON accumulation.posting, accumulation.ledger_entry, accumulation.transfer_in TO app_role;
