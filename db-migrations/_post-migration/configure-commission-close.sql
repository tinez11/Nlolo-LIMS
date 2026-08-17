-- Run by the CD pipeline immediately after Flyway migrations complete, same convention as
-- configure-billing-sweep.sql and configure-pg-partman.sql (never as a Flyway migration itself).
--
-- Why this is pg_cron and not a Java @Scheduled method: closing statements is a CROSS-TENANT
-- sweep, and a Java scheduler thread has no TenantContext, so every tenant-scoped RLS policy
-- evaluates current_setting('app.current_tenant_id', true) as NULL and the thread sees ZERO rows.
-- Documented on this platform at Application.java:7-9 and PolicyApiImpl.java:264, and the same
-- reason billing's own sweep is SQL. SECURITY DEFINER means this executes with the privileges of
-- the role that OWNS it (the migration-applying role, which owns every table in this schema),
-- bypassing RLS by virtue of table ownership. app_role itself is never granted EXECUTE on this
-- function and never bypasses RLS at any point -- this is infrastructure automation, not a
-- request-path privilege escalation.
--
-- The split this makes is the same honest one billing makes: guaranteed-on-time STATE lives here
-- in SQL; anything needing per-tenant Java (publishing events, calling a rail) stays in the
-- application. Concretely, this function deliberately does NOT trigger payouts. Closing is a pure
-- state transition, but requesting a payout needs a payeeRef that only a human can supply
-- (party.PartyView exposes no MSISDN), so that stays DistributionApi.requestStatementPayout.
CREATE OR REPLACE FUNCTION distribution.close_commission_statements() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
    -- Every OPEN statement for a period strictly before the current one closes. `period` is
    -- 'YYYY-MM' text, whose lexicographic order IS its chronological order, so a plain string
    -- comparison against to_char(now(), 'YYYY-MM') is correct and index-friendly.
    --
    -- Re-running is a no-op by construction: the OPEN-only predicate means a second pass matches
    -- nothing it already closed, so a daily cadence over a boundary crossed once a month is safe.
    -- closed_at is therefore written exactly once, and never overwritten on a later pass.
    UPDATE distribution.commission_statement
       SET status = 'CLOSED',
           closed_at = now()
     WHERE status = 'OPEN'
       AND period < to_char(now(), 'YYYY-MM');
END;
$$;

-- Postgres grants EXECUTE on a newly created function to PUBLIC by default -- unlike tables,
-- where a bare CREATE grants nothing. Every role, including app_role, could otherwise call this
-- SECURITY DEFINER function directly, which is precisely the request-path privilege escalation
-- the header comment above says must never be possible: app_role is NOSUPERUSER NOBYPASSRLS, but
-- this function's OWNER (the migration-applying role) is not, and SECURITY DEFINER runs with the
-- owner's privileges regardless of who calls it. Revoking PUBLIC's default closes that gap
-- without affecting the real caller: pg_cron records each job's scheduling role in cron.job
-- .username (verified empirically -- 'postgres' here, since the migration applies as that role),
-- not app_role, so the actual cron.schedule(...) call below is unaffected.
REVOKE EXECUTE ON FUNCTION distribution.close_commission_statements() FROM PUBLIC;

-- Daily at 01:00. A month boundary is crossed once, so daily is ample; the OPEN-only predicate
-- above is what makes the other 30 runs a no-op rather than a repeated write.
--
-- SELECT, not CALL. This is a FUNCTION (RETURNS void), and Postgres reserves CALL for
-- PROCEDUREs -- `CALL distribution.close_commission_statements()` would fail at every tick with
-- "is not a procedure. HINT: To call a function, use SELECT." That is not a hypothetical: the
-- neighbouring configure-billing-sweep.sql shipped in M4 with exactly that mistake, copied from
-- configure-pg-partman.sql where CALL is correct because partman.run_maintenance_proc() really is
-- a procedure. It went unnoticed for three milestones because no test has ever executed a
-- cron.schedule command string -- CommissionCloseSweepPsqlTest now executes the real one, parsed
-- out of this file, so a future edit cannot reintroduce it silently.
SELECT cron.schedule('commission-close', '0 1 * * *', $$SELECT distribution.close_commission_statements()$$);
