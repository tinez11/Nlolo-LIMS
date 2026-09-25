-- Run by the CD pipeline immediately after Flyway migrations complete (never as part
-- of a Flyway migration itself, and never at DB bootstrap) -- pg_partman's
-- create_parent() requires each target table to already exist. This is a one-time
-- registration per table; pg_partman + pg_cron then handle ongoing partition creation
-- automatically (Deliverable 6 flagged this exact gap: pre-created partitions alone
-- are not a complete strategy without an ahead-of-need maintenance mechanism).

-- THREE CORRECTIONS, all found empirically against the REAL image
-- (infra/postgres/Dockerfile -> postgres:16.14 + postgresql-16-partman = pg_partman 5.5.0)
-- while verifying M3's final-review finding I2. As originally written, EVERY statement in
-- this file failed, and .github/workflows/ci-cd.yml invoked psql without ON_ERROR_STOP, so
-- the deploy step reported success while registering nothing at all:
--
--   1. p_interval => 'monthly' / 'yearly'
--      ERROR: Special partition interval values from old pg_partman versions (monthly) are
--      no longer supported. pg_partman 5 takes a core PostgreSQL interval literal, so these
--      are now '1 month' / '1 year'.
--   2. Overlap with the hand-written partitions in each module's V1 migration
--      ERROR: partition "loan_transaction_p20260801" would overlap partition
--      "loan_transaction_2026_08". pg_partman's naming convention (<table>_pYYYYMMDD) does
--      not match V1's (<table>_YYYY_MM), so it cannot recognise the existing partitions as
--      its own and tries to re-create those months. p_start_partition therefore points at
--      the first month/year AFTER the hand-written ones in each module's V1.
--   3. Schema. Every call below is qualified `partman.`, but the bootstrap installed the
--      extension into `public` -- fixed in infra/postgres/init/01-create-app-role.sql.template.
--
-- IDEMPOTENT, so it can run on every deploy (scripts/configure-db.sh). create_parent refuses a
-- table pg_partman already manages ("already exists"), so as first written this file could run
-- exactly once per database -- and a second run failed the whole install. Each registration is
-- now skipped when part_config already has the table.
DO $$
BEGIN
-- Monthly, high write-velocity ledgers: pre-create 4 months ahead.
IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'policyloan.loan_transaction') THEN
    PERFORM partman.create_parent(
        p_parent_table => 'policyloan.loan_transaction',
        p_control => 'occurred_at',
        p_interval => '1 month',
        p_premake => 4,
        p_start_partition => '2026-10-01'   -- V1 hand-wrote 2026-08 and 2026-09
);
END IF;
IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'payment.payment_transaction') THEN
    PERFORM partman.create_parent(
        p_parent_table => 'payment.payment_transaction',
        p_control => 'created_at',
        p_interval => '1 month',
        p_premake => 4,
        p_start_partition => '2026-10-01'
);
END IF;
IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'payment.disbursement_instruction') THEN
    PERFORM partman.create_parent(
        p_parent_table => 'payment.disbursement_instruction',
        p_control => 'created_at',
        p_interval => '1 month',
        p_premake => 4,
        p_start_partition => '2026-10-01'
);
END IF;
IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'finaccounting.gl_posting') THEN
    PERFORM partman.create_parent(
        p_parent_table => 'finaccounting.gl_posting',
        p_control => 'created_at',
        p_interval => '1 month',
        p_premake => 4,
        p_start_partition => '2026-10-01'
);
END IF;
IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'audit.audit_log') THEN
    PERFORM partman.create_parent(
        p_parent_table => 'audit.audit_log',
        p_control => 'occurred_at',
        p_interval => '1 month',
        p_premake => 4,
        p_start_partition => '2026-10-01'
);
END IF;

-- Yearly, lower write-velocity, high row count.
IF NOT EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = 'billing.premium_invoice') THEN
    PERFORM partman.create_parent(
        p_parent_table => 'billing.premium_invoice',
        p_control => 'due_date',
        p_interval => '1 year',
        p_premake => 2,
        -- M4 billing/V2 hand-wrote a 2028 partition too (BillingApiImpl's 12-month schedule
        -- horizon, applied twice in a row across a suspend/resume cycle, reaches past 2027 --
        -- see that migration's own comment), so p_start_partition moves to 2029 for the same
        -- overlap reason p_start_partition already skips past every other table's hand-written
        -- months/years above.
        p_start_partition => '2029-01-01'   -- V1 hand-wrote 2026/2027; billing/V2 hand-wrote 2028
);
END IF;
END
$$;

-- Retention (auto-drop old partitions): explicitly NOT configured below --
-- Deliverable 6 flagged audit_log/financial-ledger retention as a compliance
-- decision (TIRA tamper-evidence expectations, general records-retention law),
-- not an architecture one. Default here is "create ahead, never auto-drop" for
-- every one of these tables until Legal/Compliance confirms real retention periods
-- per table. Setting p_retention below (with p_retention_keep_table => false) is
-- how auto-drop would be enabled once that's confirmed -- do not set it from a
-- guess.

-- pg_cron schedule: run partman's maintenance daily. This single job covers every
-- table registered above.
--
-- SECURITY NOTE (M3 final review, I2): the partitions this job creates are NOT born with
-- the parent's RLS, tenant policy, or append-only REVOKE -- Postgres cascades none of the
-- three to a partition, and pg_partman has no concept of row security at all (verified:
-- zero occurrences of relrowsecurity/pg_policy/"ROW LEVEL SECURITY" in pg_partman 5.5.0's
-- extension SQL, so its template-table facility cannot carry them). That is handled by the
-- `trg_partition_controls` event trigger in db-migrations/policyloan/V2, which mirrors the
-- parent's controls onto every new partition. If that migration is ever reverted, this job
-- starts silently minting cross-tenant-readable, mutable partitions of the platform's
-- financial ledgers.
SELECT cron.schedule('pg-partman-maintenance', '0 2 * * *', $$CALL partman.run_maintenance_proc()$$);
