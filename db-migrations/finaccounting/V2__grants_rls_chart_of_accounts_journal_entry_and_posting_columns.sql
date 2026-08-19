-- Module: finaccounting V2 -- M9 Task 1.
--
-- V1 shipped as an explicitly PROVISIONAL skeleton gated on C1 (actuarial
-- cohort/grouping rules), and carries the recurring V1 defect set this project has
-- now caught in six consecutive modules: no RLS anywhere, no GRANTs at all, no
-- optimistic locking, no money guards.
--
-- Most consequentially: V1 ends with
--   REVOKE UPDATE, DELETE ON finaccounting.gl_posting FROM app_role;
-- with no prior GRANT. app_role therefore has NO privileges on the ledger at all --
-- the append-only *intent* was expressed, the ability to append never was. The
-- platform's append-only ledger has never once been writable, nor verified.
--
-- SCOPE BOUNDARY, load-bearing: this migration does NOT touch csm_ledger,
-- lrc_ledger or lic_ledger beyond hardening them. Populating them is IFRS 17
-- measurement, which is blocked on C1 (Actuarial) and lands in audited financial
-- statements. See docs/superpowers/specs/2026-08-19-m9-finaccounting-design.md §1.
-- =============================================================================
-- 1. Grants. V1 grants app_role nothing anywhere in the schema.
-- =============================================================================
GRANT USAGE ON SCHEMA finaccounting TO app_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA finaccounting TO app_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA finaccounting GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_role;

-- gl_posting is an append-only ledger (docs/06-database-schema.md:33). The blanket
-- grant above deliberately runs FIRST so this REVOKE has something to revoke --
-- V1's REVOKE-without-GRANT is exactly why app_role could not write the table.
-- Order matters: grant, then narrow.
REVOKE UPDATE, DELETE ON finaccounting.gl_posting FROM app_role;

-- =============================================================================
-- 2. RLS on all five existing tables. V1 enabled it on NONE of them, while every
--    one carries tenant_id NOT NULL -- so app_role could read every tenant's
--    ledger, balances and contract groups.
-- =============================================================================
ALTER TABLE finaccounting.group_of_contracts ENABLE ROW LEVEL SECURITY;
CREATE POLICY group_of_contracts_tenant_isolation ON finaccounting.group_of_contracts
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE finaccounting.csm_ledger ENABLE ROW LEVEL SECURITY;
CREATE POLICY csm_ledger_tenant_isolation ON finaccounting.csm_ledger
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE finaccounting.lrc_ledger ENABLE ROW LEVEL SECURITY;
CREATE POLICY lrc_ledger_tenant_isolation ON finaccounting.lrc_ledger
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE finaccounting.lic_ledger ENABLE ROW LEVEL SECURITY;
CREATE POLICY lic_ledger_tenant_isolation ON finaccounting.lic_ledger
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

ALTER TABLE finaccounting.gl_posting ENABLE ROW LEVEL SECURITY;
CREATE POLICY gl_posting_tenant_isolation ON finaccounting.gl_posting
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- =============================================================================
-- 3. C1 marker on the three measurement ledgers. These tables are real and
--    hardened, but NOTHING in M9 writes them: populating them is CSM roll-forward
--    / LRC / LIC computation, which requires the GMM-vs-PAA and cohort decisions
--    that Actuarial owns (docs/02-module-architecture.md:197). A wrong roll-forward
--    is a financial misstatement, not a fixable bug, so M9 declines to guess.
-- =============================================================================
COMMENT ON TABLE finaccounting.csm_ledger IS
    'C1-BLOCKED -- DO NOT POPULATE. CSM roll-forward requires the actuarial GMM/PAA and cohort decision. M9 builds GL posting only.';
COMMENT ON TABLE finaccounting.lrc_ledger IS
    'C1-BLOCKED -- DO NOT POPULATE. LRC release is how premium income is earned; M9 accumulates unearned premium (2200) instead and recognises no income.';
COMMENT ON TABLE finaccounting.lic_ledger IS
    'C1-BLOCKED -- DO NOT POPULATE. Requires the actuarial measurement decision.';

-- =============================================================================
-- 4. Missing tenant indexes and audit columns. docs/06-database-schema.md:25
--    requires tenant_id indexed on every tenant-scoped table.
-- =============================================================================
CREATE INDEX idx_csm_ledger_tenant ON finaccounting.csm_ledger (tenant_id);
CREATE INDEX idx_lrc_ledger_tenant ON finaccounting.lrc_ledger (tenant_id);
CREATE INDEX idx_lic_ledger_tenant ON finaccounting.lic_ledger (tenant_id);
CREATE INDEX idx_gl_posting_tenant ON finaccounting.gl_posting (tenant_id);

ALTER TABLE finaccounting.lrc_ledger ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE finaccounting.lic_ledger ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- group_of_contracts is the one mutable aggregate root here (status changes as a
-- group opens/closes). The ledgers are append-only or C1-blocked, so no version.
ALTER TABLE finaccounting.group_of_contracts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE finaccounting.group_of_contracts ADD COLUMN created_by VARCHAR(100);
ALTER TABLE finaccounting.group_of_contracts ADD COLUMN updated_at TIMESTAMPTZ;
ALTER TABLE finaccounting.group_of_contracts ADD COLUMN updated_by VARCHAR(100);

-- =============================================================================
-- 5. chart_of_account -- finaccounting's own core reference data.
--
--    EVERY ACCOUNT BELOW IS AN INVENTED PLACEHOLDER pending Finance sign-off. No
--    document on this platform specifies account codes or their debit/credit
--    treatment (grepped all of db-migrations/refdata: nothing). Codes follow the
--    conventional five-block scheme (1xxx ASSET, 2xxx LIABILITY, 3xxx EQUITY,
--    4xxx INCOME, 5xxx EXPENSE) so Finance's real chart is more likely a
--    re-mapping of familiar blocks than a redesign.
--
--    Owned here rather than in refdata because a chart of accounts is STRUCTURED
--    reference data (type, normal balance) that refdata's flat key/value
--    reference_code_set models poorly.
-- =============================================================================
CREATE TABLE finaccounting.chart_of_account (
    tenant_id           UUID NOT NULL,
    account_code         VARCHAR(20) NOT NULL,
    name                  VARCHAR(200) NOT NULL,
    account_type           VARCHAR(20) NOT NULL
        CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    normal_balance          VARCHAR(2) NOT NULL CHECK (normal_balance IN ('DR','CR')),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                VARCHAR(100),
    PRIMARY KEY (tenant_id, account_code)
);
CREATE INDEX idx_chart_of_account_tenant ON finaccounting.chart_of_account (tenant_id);

ALTER TABLE finaccounting.chart_of_account ENABLE ROW LEVEL SECURITY;
CREATE POLICY chart_of_account_tenant_isolation ON finaccounting.chart_of_account
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON finaccounting.chart_of_account TO app_role;

-- =============================================================================
-- 6. journal_entry -- the aggregate root, and WHERE IDEMPOTENCY LIVES.
--
--    Why not a unique index on gl_posting directly: gl_posting is
--    PARTITION BY RANGE (created_at), and Postgres requires every unique index on
--    a partitioned table to include all partition-key columns --
--      ERROR: unique constraint on partitioned table must include all
--             partitioning columns
--    (verified empirically against postgres:16 while planning M9). Omitting
--    created_at is rejected outright; INCLUDING it is worse than the error,
--    because it applies cleanly and then silently permits a double-post -- a
--    redelivered event arriving at a different timestamp satisfies the constraint.
--    A duplicate journal entry is exactly what distribution.CommissionPaid's and
--    reinsurance.RecoveryConfirmed's own code comments warn about.
--
--    This is also the better model: in double-entry bookkeeping the ENTRY is the
--    transaction and the postings are its legs.
-- =============================================================================
CREATE TABLE finaccounting.journal_entry (
    journal_entry_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    source_event           VARCHAR(60) NOT NULL,
    source_ref              VARCHAR(100) NOT NULL,
    period                   VARCHAR(7) NOT NULL,
    policy_number             VARCHAR(20),      -- opaque ref into policy; never an FK
    posted_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100)
);
CREATE UNIQUE INDEX ux_journal_entry_once
    ON finaccounting.journal_entry (tenant_id, source_event, source_ref);
CREATE INDEX idx_journal_entry_tenant ON finaccounting.journal_entry (tenant_id);
CREATE INDEX idx_journal_entry_period ON finaccounting.journal_entry (tenant_id, period);
CREATE INDEX idx_journal_entry_policy ON finaccounting.journal_entry (tenant_id, policy_number);

ALTER TABLE finaccounting.journal_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY journal_entry_tenant_isolation ON finaccounting.journal_entry
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- Append-only on the same terms as gl_posting: a posted entry is never amended.
GRANT SELECT, INSERT ON finaccounting.journal_entry TO app_role;
REVOKE UPDATE, DELETE ON finaccounting.journal_entry FROM app_role;

-- =============================================================================
-- 7. gl_posting becomes a real double-entry line.
--
--    V1's columns are (posting_id, tenant_id, group_id, period, amount, currency,
--    posting_type, created_at) -- NO account code and NO debit/credit indicator.
--    That cannot represent a double-entry posting at all.
--
--    group_id becomes NULLABLE because a "group of insurance contracts" IS the
--    C1-governed IFRS 17 unit of account. Keeping it NOT NULL would force
--    inventing a group, and group_of_contracts.measurement_model's CHECK would
--    then force inventing the GMM-vs-PAA answer too -- smuggling in the exact
--    decision this milestone defers.
-- =============================================================================
ALTER TABLE finaccounting.gl_posting ALTER COLUMN group_id DROP NOT NULL;

ALTER TABLE finaccounting.gl_posting ADD COLUMN journal_entry_id UUID;
ALTER TABLE finaccounting.gl_posting ADD COLUMN account_code VARCHAR(20);
ALTER TABLE finaccounting.gl_posting ADD COLUMN direction VARCHAR(2);
ALTER TABLE finaccounting.gl_posting ADD COLUMN policy_number VARCHAR(20);
ALTER TABLE finaccounting.gl_posting ADD COLUMN source_event VARCHAR(60);
ALTER TABLE finaccounting.gl_posting ADD COLUMN source_ref VARCHAR(100);

-- posting_type MUST be widened, and this is not cosmetic. V1 declared it VARCHAR(30);
-- GlPosting populates it with the source event name (V1 made it NOT NULL with no default and
-- V1 is immutable, so it cannot simply be left unset), and 'billing.PremiumInvoiceGenerated'
-- is 31 characters -- MEASURED, not estimated. Left at 30, the single most important posting
-- path on the platform would fail at runtime with
--   value too long for type character varying(30)
-- while every pure unit test stayed green, because none of them touch the database.
--
-- This is exactly the defect class documented at docs/06-database-schema.md:30's sub-bullet
-- (M7's VARCHAR(15) column admitting a 16-character CHECK value, which made a whole payout
-- path unwritable). It is being fixed here rather than discovered in Task 6.
-- 60 matches source_event's width, so the two columns cannot drift apart again.
ALTER TABLE finaccounting.gl_posting ALTER COLUMN posting_type TYPE VARCHAR(60);

-- Added nullable above then constrained here: no rows exist (nothing has ever
-- written this table -- app_role could not), but a bare NOT NULL ADD COLUMN on a
-- populated table would fail, and succeeding only because the table happens to be
-- empty is the kind of thing that breaks on the first deployment that has data.
UPDATE finaccounting.gl_posting SET journal_entry_id = gen_random_uuid() WHERE journal_entry_id IS NULL;
UPDATE finaccounting.gl_posting SET account_code = '1000' WHERE account_code IS NULL;
UPDATE finaccounting.gl_posting SET direction = 'DR' WHERE direction IS NULL;
UPDATE finaccounting.gl_posting SET source_event = 'legacy' WHERE source_event IS NULL;
UPDATE finaccounting.gl_posting SET source_ref = 'legacy' WHERE source_ref IS NULL;

ALTER TABLE finaccounting.gl_posting ALTER COLUMN journal_entry_id SET NOT NULL;
ALTER TABLE finaccounting.gl_posting ALTER COLUMN account_code SET NOT NULL;
ALTER TABLE finaccounting.gl_posting ALTER COLUMN direction SET NOT NULL;
ALTER TABLE finaccounting.gl_posting ALTER COLUMN source_event SET NOT NULL;
ALTER TABLE finaccounting.gl_posting ALTER COLUMN source_ref SET NOT NULL;

-- direction VARCHAR(2) against a longest value of 'DR'/'CR' (2). Deliberately
-- verified rather than assumed: M7 shipped a CHECK admitting a 16-character value
-- into a VARCHAR(15), making a whole payout path unwritable while tests stayed green.
ALTER TABLE finaccounting.gl_posting
    ADD CONSTRAINT gl_posting_direction_check CHECK (direction IN ('DR','CR'));

-- amount is a POSITIVE MAGNITUDE, strictly. The direction column carries the sign,
-- so a reversal is a NEW entry with the two directions swapped, never a negative
-- amount on the original. Signed amounts would also make the "every entry
-- balances" assertion ambiguous about whether a negative DR is really a CR.
ALTER TABLE finaccounting.gl_posting
    ADD CONSTRAINT gl_posting_amount_positive CHECK (amount > 0);

CREATE INDEX idx_gl_posting_entry ON finaccounting.gl_posting (journal_entry_id);
CREATE INDEX idx_gl_posting_account ON finaccounting.gl_posting (tenant_id, account_code, period);

-- =============================================================================
-- 8. Seed the chart of accounts. PLACEHOLDERS -- Finance sign-off required.
--
--    Seeded for the ONE well-known dev/test tenant only; a real deployment seeds
--    per tenant during onboarding. No 4xxx INCOME account is seeded, deliberately:
--    M9 never credits income (premium is earned via LRC release, which is
--    C1-blocked), and seeding an account nothing posts to would imply coverage
--    this milestone does not have.
-- =============================================================================
-- NOTE for the implementer: this INSERT is intentionally left OUT of the
-- migration. Seeding a hardcoded tenant_id into a shared migration would create a
-- row every tenant's RLS hides and nobody uses. Instead, ChartOfAccountSeeder
-- (Task 3) seeds these nine accounts per tenant on first use, and the tests seed
-- explicitly. The nine accounts are:
--   1000 Cash / Mobile Money        ASSET      DR
--   1200 Premium Receivable         ASSET      DR
--   1300 Reinsurance Recoverable    ASSET      DR
--   1400 Policy Loan Receivable     ASSET      DR
--   2200 Unearned Premium           LIABILITY  CR
--   2300 Reinsurance Payable        LIABILITY  CR
--   5000 Claims Expense             EXPENSE    DR
--   5100 Commission Expense         EXPENSE    DR
--   5200 Reinsurance Ceded Premium  EXPENSE    DR
