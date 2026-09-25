-- What the platform needs installed OUTSIDE its numbered migrations, and whether it is.
--
-- The backend connects as app_role, which cannot read cron.job (pg_cron shows each role only its
-- own jobs, and these belong to the superuser that installed them) nor partman.part_config. So
-- it could not tell a database with every sweep installed from one with none -- and for months
-- that was exactly the situation: commission never closed, loans never accrued interest, and the
-- monthly ledgers had partitions only to September 2026, while nothing anywhere failed.
--
-- This SECURITY DEFINER function answers one question for the backend's health check: is each
-- expected job scheduled, and is each partitioned table under pg_partman's maintenance?
--
-- THE LIST BELOW MUST MATCH WHAT scripts/configure-db.sh INSTALLS. Add a job there, add it here.
--
-- Applied LAST by scripts/configure-db.sh, after everything it checks. Idempotent.

CREATE SCHEMA IF NOT EXISTS ops;

CREATE OR REPLACE FUNCTION ops.platform_readiness()
RETURNS TABLE (item text, present boolean)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT 'job ' || j, EXISTS (SELECT 1 FROM cron.job WHERE jobname = j)
    FROM unnest(ARRAY[
        'billing-sweep',
        'commission-close',
        'offer-expiry-sweep',
        'offer-reminder-sweep',
        'loan-interest-accrual',
        'pg-partman-maintenance']) AS j
    UNION ALL
    SELECT 'partitions ' || t, EXISTS (SELECT 1 FROM partman.part_config WHERE parent_table = t)
    FROM unnest(ARRAY[
        'policyloan.loan_transaction',
        'payment.payment_transaction',
        'payment.disbursement_instruction',
        'finaccounting.gl_posting',
        'audit.audit_log',
        'billing.premium_invoice']) AS t
$$;

REVOKE ALL ON FUNCTION ops.platform_readiness() FROM PUBLIC;
GRANT USAGE ON SCHEMA ops TO app_role;
GRANT EXECUTE ON FUNCTION ops.platform_readiness() TO app_role;
