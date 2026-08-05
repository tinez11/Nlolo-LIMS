-- Run by the CD pipeline immediately after Flyway migrations complete (never as part
-- of a Flyway migration itself, and never at DB bootstrap) -- pg_partman's
-- create_parent() requires each target table to already exist. This is a one-time
-- registration per table; pg_partman + pg_cron then handle ongoing partition creation
-- automatically (Deliverable 6 flagged this exact gap: pre-created partitions alone
-- are not a complete strategy without an ahead-of-need maintenance mechanism).

-- Monthly, high write-velocity ledgers: pre-create 4 months ahead.
SELECT partman.create_parent(
    p_parent_table => 'policyloan.loan_transaction',
    p_control => 'occurred_at',
    p_interval => 'monthly',
    p_premake => 4
);
SELECT partman.create_parent(
    p_parent_table => 'payment.payment_transaction',
    p_control => 'created_at',
    p_interval => 'monthly',
    p_premake => 4
);
SELECT partman.create_parent(
    p_parent_table => 'payment.disbursement_instruction',
    p_control => 'created_at',
    p_interval => 'monthly',
    p_premake => 4
);
SELECT partman.create_parent(
    p_parent_table => 'finaccounting.gl_posting',
    p_control => 'created_at',
    p_interval => 'monthly',
    p_premake => 4
);
SELECT partman.create_parent(
    p_parent_table => 'audit.audit_log',
    p_control => 'occurred_at',
    p_interval => 'monthly',
    p_premake => 4
);

-- Yearly, lower write-velocity, high row count.
SELECT partman.create_parent(
    p_parent_table => 'billing.premium_invoice',
    p_control => 'due_date',
    p_interval => 'yearly',
    p_premake => 2
);

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
SELECT cron.schedule('pg-partman-maintenance', '0 2 * * *', $$CALL partman.run_maintenance_proc()$$);
