-- Run by the CD pipeline immediately after Flyway migrations complete, same convention as
-- configure-pg-partman.sql (never as a Flyway migration itself). SECURITY DEFINER means this
-- function executes with the PRIVILEGES OF THE ROLE THAT OWNS IT (the migration-applying role,
-- which owns every table in this schema), bypassing RLS by virtue of table ownership -- the
-- exact same class of DB-internal, non-app_role privilege db-migrations/policyloan/
-- V2__partition_tenant_controls.sql's ddl_command_end event trigger already established and
-- passed M3's final review for.
--
-- M7 correction: this comment claimed "app_role itself is never granted EXECUTE on this function"
-- from M4 until now, and that claim was FALSE. Postgres grants EXECUTE on a newly created function
-- to PUBLIC by default -- unlike tables, where a bare CREATE grants nothing -- and nothing here
-- ever revoked it, so app_role (and every other role) could call this SECURITY DEFINER function
-- directly the whole time. Found while adding distribution's equivalent close sweep in M7 and
-- checking this file for the same class of gap. Fixed below with an explicit REVOKE; the actual
-- caller is unaffected, because pg_cron records each job's scheduling role in cron.job.username
-- (verified empirically as 'postgres', the migration-applying role), never app_role.
-- See the M4 plan's Global Constraints for the full reasoning and the
-- honest split this design makes between guaranteed-on-time state (this function) and
-- eventually-consistent notification (BillingApiImpl.publishPendingNotifications, Task 4).
CREATE OR REPLACE FUNCTION billing.sweep_billing_state() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_overdue_receipt_count INTEGER;
BEGIN
    -- 1. Invoices whose grace period has fully expired move to OVERDUE.
    UPDATE billing.premium_invoice
       SET status = 'OVERDUE'
     WHERE status IN ('DUE', 'IN_GRACE')
       AND grace_period_ends_at IS NOT NULL
       AND grace_period_ends_at < CURRENT_DATE;

    -- 2. Invoices past due_date but still within their grace window move to IN_GRACE (from DUE).
    UPDATE billing.premium_invoice
       SET status = 'IN_GRACE'
     WHERE status = 'DUE'
       AND due_date < CURRENT_DATE
       AND (grace_period_ends_at IS NULL OR grace_period_ends_at >= CURRENT_DATE);

    -- 3. Open an ArrearsCase (dunning level 1) for every newly-OVERDUE invoice without one.
    INSERT INTO billing.arrears_case (tenant_id, invoice_id, policy_number, dunning_level, opened_at)
    SELECT pi.tenant_id, pi.invoice_id, pi.policy_number, 1, now()
      FROM billing.premium_invoice pi
     WHERE pi.status = 'OVERDUE'
       AND NOT EXISTS (
           SELECT 1 FROM billing.arrears_case ac
            WHERE ac.invoice_id = pi.invoice_id AND ac.resolved_at IS NULL
       );

    -- 4. Escalate dunning level on every open ArrearsCase per the refdata-configured thresholds.
    -- Level only ever increases here -- BillingApiImpl.publishPendingNotifications (Task 4) is
    -- what compares against last_notified_dunning_level and never runs backwards either.
    UPDATE billing.arrears_case ac
       SET dunning_level = sub.new_level
      FROM (
          SELECT ac2.arrears_case_id,
                 CASE
                     WHEN now() - ac2.opened_at >= (
                         (SELECT value FROM refdata.reference_code_set WHERE code_set_key = 'DUNNING_ESCALATION_DAYS' AND code = 'LEVEL_5') || ' days')::interval THEN 5
                     WHEN now() - ac2.opened_at >= (
                         (SELECT value FROM refdata.reference_code_set WHERE code_set_key = 'DUNNING_ESCALATION_DAYS' AND code = 'LEVEL_4') || ' days')::interval THEN 4
                     WHEN now() - ac2.opened_at >= (
                         (SELECT value FROM refdata.reference_code_set WHERE code_set_key = 'DUNNING_ESCALATION_DAYS' AND code = 'LEVEL_3') || ' days')::interval THEN 3
                     WHEN now() - ac2.opened_at >= (
                         (SELECT value FROM refdata.reference_code_set WHERE code_set_key = 'DUNNING_ESCALATION_DAYS' AND code = 'LEVEL_2') || ' days')::interval THEN 2
                     ELSE 1
                 END AS new_level
            FROM billing.arrears_case ac2
           WHERE ac2.resolved_at IS NULL
      ) sub
     WHERE ac.arrears_case_id = sub.arrears_case_id
       AND sub.new_level > ac.dunning_level;

    -- 5. Field receipts unmatched by a PaymentConfirmed (which never arrives in M4 -- payment
    -- is M5) within the refdata-configured SLA move to RECONCILIATION_OVERDUE.
    UPDATE billing.field_receipt
       SET status = 'RECONCILIATION_OVERDUE'
     WHERE status = 'PENDING_RECONCILIATION'
       AND captured_at_server < now() - (
           (SELECT value FROM refdata.reference_code_set WHERE code_set_key = 'OFFLINE_RECEIPT_SLA_HOURS' AND code = 'DEFAULT') || ' hours')::interval;

    -- 6. Refresh the global, non-tenant-scoped metrics snapshot the Micrometer gauge reads.
    SELECT count(*) INTO v_overdue_receipt_count FROM billing.field_receipt WHERE status = 'RECONCILIATION_OVERDUE';
    UPDATE billing.overdue_metrics_snapshot SET field_receipt_overdue_count = v_overdue_receipt_count, computed_at = now() WHERE id = 1;
END;
$$;

-- See the correction above this function's own header: Postgres grants EXECUTE to PUBLIC by
-- default on every new function, and this REVOKE never existed until M7. Safe to add now --
-- verified pg_cron calls this as 'postgres' (cron.job.username), never as app_role.
REVOKE EXECUTE ON FUNCTION billing.sweep_billing_state() FROM PUBLIC;

-- Every 15 minutes -- tighter than pg_partman's daily maintenance cadence, since a 24-hour SLA
-- needs sub-hour granularity to be meaningfully enforced, and the alert rule's own `for: 10m`
-- window implies the underlying metric is expected to move on a similar timescale.
--
-- M7 fix: this line said CALL until now, and CALL was wrong. sweep_billing_state is a FUNCTION
-- (RETURNS void, declared above); Postgres reserves CALL for PROCEDUREs and rejects it with
-- "billing.sweep_billing_state() is not a procedure. HINT: To call a function, use SELECT."
-- (verified against a real postgres:16). So this job has been failing on EVERY 15-minute tick
-- since M4 -- meaning none of billing's guaranteed-on-time state ever ran in a deployed
-- environment: no invoice reached IN_GRACE or OVERDUE by sweep, no ArrearsCase was opened or
-- escalated, no field receipt hit RECONCILIATION_OVERDUE, and PolicyLapseRecommended (which fires
-- at dunning level >= 5) could never be reached.
--
-- Three things hid it. The CALL idiom was copied from configure-pg-partman.sql, where it is
-- correct because partman.run_maintenance_proc() genuinely IS a procedure. BillingSweepPsqlTest
-- invokes the function directly with SELECT and strips this line, so the command string pg_cron
-- actually runs was never executed by any test. And pg_cron_job_failed_total is on
-- AlertRuleMetricProducerTest's KNOWINGLY_UNPRODUCED list, so a job failing forever raises
-- nothing -- the failure is recorded only in cron.job_run_details, which nothing reads.
SELECT cron.schedule('billing-sweep', '*/15 * * * *', $$SELECT billing.sweep_billing_state()$$);
