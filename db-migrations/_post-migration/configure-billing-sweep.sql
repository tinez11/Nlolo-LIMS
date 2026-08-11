-- Run by the CD pipeline immediately after Flyway migrations complete, same convention as
-- configure-pg-partman.sql (never as a Flyway migration itself). SECURITY DEFINER means this
-- function executes with the PRIVILEGES OF THE ROLE THAT OWNS IT (the migration-applying role,
-- which owns every table in this schema), bypassing RLS by virtue of table ownership -- the
-- exact same class of DB-internal, non-app_role privilege db-migrations/policyloan/
-- V2__partition_tenant_controls.sql's ddl_command_end event trigger already established and
-- passed M3's final review for. app_role itself is never granted EXECUTE on this function and
-- never bypasses RLS at any point -- this is infrastructure automation, not a request-path
-- privilege escalation. See the M4 plan's Global Constraints for the full reasoning and the
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

-- Every 15 minutes -- tighter than pg_partman's daily maintenance cadence, since a 24-hour SLA
-- needs sub-hour granularity to be meaningfully enforced, and the alert rule's own `for: 10m`
-- window implies the underlying metric is expected to move on a similar timescale.
SELECT cron.schedule('billing-sweep', '*/15 * * * *', $$CALL billing.sweep_billing_state()$$);
