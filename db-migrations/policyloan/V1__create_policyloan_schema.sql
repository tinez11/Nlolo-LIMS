-- Module: policyloan (Policy Loans & Cash Value) -- Deliverable 3 Rev 2 §4
-- Owns: policy_loan, loan_interest_term, repayment_schedule, loan_transaction (partitioned)

CREATE SCHEMA IF NOT EXISTS policyloan;

CREATE TABLE policyloan.policy_loan (
    loan_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    policy_number       VARCHAR(20) NOT NULL,        -- opaque ref into policy -- no cross-schema FK
    principal_amount    NUMERIC(19,2) NOT NULL,
    principal_currency  CHAR(3) NOT NULL DEFAULT 'TZS',
    status              VARCHAR(30) NOT NULL DEFAULT 'RESERVED_PENDING_ORIGINATION' CHECK (status IN
        ('RESERVED_PENDING_ORIGINATION','ORIGINATED','DISBURSEMENT_REQUESTED','DISBURSED','REPAYING','SETTLED','FORCED_LAPSE_TRIGGERED')),
    originated_at       TIMESTAMPTZ,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100)
);
CREATE INDEX idx_policy_loan_tenant ON policyloan.policy_loan (tenant_id);
CREATE INDEX idx_policy_loan_policy ON policyloan.policy_loan (policy_number);   -- IC1
CREATE INDEX idx_policy_loan_status ON policyloan.policy_loan (tenant_id, status);

-- Deliverable 3 Rev 2, L1: interest rate tracked with effective-dating rather than a
-- single immutable field, so "locked at origination" vs "floating" is a data question,
-- not a schema question, once B2 is finally decided.
CREATE TABLE policyloan.loan_interest_term (
    loan_interest_term_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    loan_id               UUID NOT NULL REFERENCES policyloan.policy_loan(loan_id),
    rate                  NUMERIC(7,4) NOT NULL,
    effective_from        DATE NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_loan_interest_term_loan ON policyloan.loan_interest_term (loan_id, effective_from);

-- Deliverable 3 Rev 2, L2: optional -- present only for products with fixed installment
-- terms; ad hoc repayment against cash value is always supported regardless, via loan_transaction.
CREATE TABLE policyloan.repayment_schedule (
    repayment_schedule_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    loan_id               UUID NOT NULL REFERENCES policyloan.policy_loan(loan_id),
    installment_number    INTEGER NOT NULL,
    due_date              DATE NOT NULL,
    scheduled_amount      NUMERIC(19,2) NOT NULL,
    scheduled_currency    CHAR(3) NOT NULL DEFAULT 'TZS'
);
CREATE INDEX idx_repayment_schedule_loan ON policyloan.repayment_schedule (loan_id, due_date);

-- Append-only ledger -- authoritative source for outstanding balance (never the
-- schedule). Partitioned by month on occurred_at: this is the highest-volume table
-- in this module (every disbursement, repayment, interest accrual, and settlement
-- entry lands here).
CREATE TABLE policyloan.loan_transaction (
    loan_transaction_id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    loan_id             UUID NOT NULL,
    transaction_type    VARCHAR(20) NOT NULL CHECK (transaction_type IN ('DISBURSEMENT','REPAYMENT','INTEREST_ACCRUAL','SETTLEMENT','REVERSAL')),
    amount              NUMERIC(19,2) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'TZS',
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    reference           VARCHAR(255),
    PRIMARY KEY (loan_transaction_id, occurred_at)   -- partition key must be part of the PK
) PARTITION BY RANGE (occurred_at);

CREATE TABLE policyloan.loan_transaction_2026_08 PARTITION OF policyloan.loan_transaction
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE policyloan.loan_transaction_2026_09 PARTITION OF policyloan.loan_transaction
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
-- Additional monthly partitions created ahead of need by a scheduled job (pg_partman
-- or an equivalent Flyway-driven maintenance script) -- see Deliverable 6 covering
-- doc §3 for the operational convention; do not hand-write partitions indefinitely.

CREATE INDEX idx_loan_transaction_loan ON policyloan.loan_transaction (loan_id, occurred_at);
CREATE INDEX idx_loan_transaction_tenant ON policyloan.loan_transaction (tenant_id);

-- Defense-in-depth RLS (Global Constraints) -- policy_loan already had it; the
-- remaining 3 tenant-scoped tables in this schema did not.
ALTER TABLE policyloan.policy_loan ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_loan_tenant_isolation ON policyloan.policy_loan
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policyloan.loan_interest_term ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_interest_term_tenant_isolation ON policyloan.loan_interest_term
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policyloan.repayment_schedule ENABLE ROW LEVEL SECURITY;
CREATE POLICY repayment_schedule_tenant_isolation ON policyloan.repayment_schedule
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- loan_transaction is PARTITIONED BY RANGE. Verified empirically (throwaway Postgres 16
-- container, not taken on faith) that `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` on the
-- PARENT table (no ONLY) does NOT cascade the enable flag to existing partitions --
-- pg_class.relrowsecurity stays FALSE on each partition, because a partition is a
-- separate relation with its own independent row-security setting. This is NOT
-- Postgres's RLS default-deny (a claim in an earlier draft of this comment that this
-- same empirical test disproved): with RLS off on the partition, a direct query against
-- a dated partition (e.g. policyloan.loan_transaction_2026_08) by any role, including
-- app_role, returns EVERY tenant's rows, completely unfiltered -- a full cross-tenant
-- data leak on the highest-volume table in this schema, not a merely-inconvenient
-- zero-row result. Querying through the parent by name -- the only access path this
-- application uses today -- IS correctly filtered, because Postgres evaluates RLS
-- against the relation actually named in the query, and the parent does have RLS
-- enabled with a policy below.
-- Defense in depth: RLS + the identical tenant-isolation policy is therefore also
-- enabled explicitly on BOTH existing partitions, not just the parent -- mirroring how
-- the REVOKE further down already has to name each partition explicitly.
-- OPERATIONAL TRAP FOR FUTURE PARTITIONS: neither the parent's ENABLE ROW LEVEL
-- SECURITY nor its CREATE POLICY is inherited by a partition created later (by
-- pg_partman or a future Flyway migration). Any such partition needs its own
-- ENABLE ROW LEVEL SECURITY + CREATE POLICY <name>_tenant_isolation (and its own
-- REVOKE UPDATE, DELETE, see below) or it will be silently readable, and
-- writable/deletable, across every tenant.
ALTER TABLE policyloan.loan_transaction ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_transaction_tenant_isolation ON policyloan.loan_transaction
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policyloan.loan_transaction_2026_08 ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_transaction_2026_08_tenant_isolation ON policyloan.loan_transaction_2026_08
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE policyloan.loan_transaction_2026_09 ENABLE ROW LEVEL SECURITY;
CREATE POLICY loan_transaction_2026_09_tenant_isolation ON policyloan.loan_transaction_2026_09
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- app_role privileges -- migrations run as the postgres superuser (scripts/migrate.sh),
-- which becomes owner of every object created above; without these explicit grants
-- app_role (the application's runtime DB role) has no access to this schema at all
-- and every request against it fails with "permission denied for schema policyloan"
-- (the exact bug M1's/M2's final whole-branch reviews found and fixed for every other
-- schema -- fixed here from the start instead of waiting for a third review to catch it).
GRANT USAGE ON SCHEMA policyloan TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA policyloan TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA policyloan GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- Append-only ledger enforcement. MUST run AFTER the GRANT above, or the GRANT silently
-- undoes it (statement order matters -- verified empirically in the same throwaway
-- container: a REVOKE issued before a later blanket GRANT is re-granted right back).
-- Also verified empirically: GRANT/REVOKE on a partitioned PARENT table does NOT cascade
-- to its existing partitions -- each partition is an independent relation with its own
-- ACL, so "ALL TABLES IN SCHEMA" above grants UPDATE/DELETE on the partitions too and
-- revoking only on the parent name would leave a direct-partition-access loophole. Listed
-- against the parent and every existing partition explicitly; a future migration adding a
-- new monthly partition must repeat this REVOKE for that partition (ALTER DEFAULT
-- PRIVILEGES only ever grants -- it has no symmetric "default revoke").
REVOKE UPDATE, DELETE ON policyloan.loan_transaction, policyloan.loan_transaction_2026_08, policyloan.loan_transaction_2026_09 FROM app_role;
