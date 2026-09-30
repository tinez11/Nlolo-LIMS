-- Selection helper for InvoiceRollForward (product step 0, D1). Answers "which active schedules are
-- running out of pre-created invoices and have not reached the end of their contract", across every
-- tenant, for a Java drain that then raises the next batch under each schedule's tenant context.
--
-- A Java drain rather than a pg_cron UPDATE, for the reason the same split exists for offer
-- reminders and cover expiry: raising an invoice publishes billing.PremiumInvoiceGenerated, which
-- finaccounting consumes to post the premium receivable. A SQL INSERT would bill the customer and
-- leave the ledger silent. So SQL only SELECTS the work; Java raises it and the event.
--
-- SECURITY DEFINER and cross-tenant, as every sweep here is: it runs on a schedule with no request
-- and no tenant. It returns ONLY (billing_schedule_id, tenant_id); the drain sets the tenant per
-- row and does everything else under RLS.
--
-- The parameter is how many months ahead counts as "running low": a schedule is picked when its
-- next due date falls within that window. generateInvoicesAhead advances next_due_date to roughly a
-- year out on each run, so with a two-month window a schedule is rolled forward about ten months
-- before its pre-created invoices would have run out -- comfortable slack, and idempotent because
-- rollForward locks the row and re-reads the advanced date.
--
-- A schedule whose next due date has already passed its premium_paying_until is DONE and excluded;
-- one with a null paying end (whole life, an annually renewable scheme) rolls forward forever.
CREATE OR REPLACE FUNCTION billing.schedules_due_for_invoicing(horizon_months INTEGER)
RETURNS TABLE (billing_schedule_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT s.billing_schedule_id, s.tenant_id
      FROM billing.billing_schedule s
     WHERE s.status = 'ACTIVE'
       AND s.next_due_date IS NOT NULL
       AND s.next_due_date <= current_date + (horizon_months || ' months')::INTERVAL
       AND (s.premium_paying_until IS NULL OR s.next_due_date <= s.premium_paying_until)
     ORDER BY s.next_due_date
     LIMIT 500;
$$;

REVOKE EXECUTE ON FUNCTION billing.schedules_due_for_invoicing(INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing.schedules_due_for_invoicing(INTEGER) TO app_role;

COMMENT ON FUNCTION billing.schedules_due_for_invoicing(INTEGER) IS
    'Active schedules whose next due date falls within horizon_months and which have not reached premium_paying_until, across all tenants, for InvoiceRollForward. Ids only: the drain sets the tenant per row and raises invoices under RLS.';
