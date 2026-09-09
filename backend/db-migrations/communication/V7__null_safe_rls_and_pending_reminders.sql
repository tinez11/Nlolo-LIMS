-- db-migrations/communication/V7__null_safe_rls_and_pending_reminders.sql
-- Two fixes the reminder drain surfaced on its first real run, both about the same seam:
-- what happens when a query reaches Postgres with NO tenant set.
--
-- ============================================================================================
-- 1. THE RLS PREDICATE CRASHES INSTEAD OF FAILING CLOSED
-- ============================================================================================
-- Every RLS policy on this platform, in every module, is written:
--
--     USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)
--
-- and TenantAwareDataSource's javadoc states the assumption underneath it: "current_setting on a
-- not-yet-set app.current_tenant_id already returns NULL". That is true of a GUC that has NEVER
-- been set. It is NOT true of one that was SET and then RESET -- which is exactly what
-- TenantAwareDataSource does on every pooled connection borrowed without a tenant. Postgres
-- returns the EMPTY STRING for that, and ''::uuid raises
-- "invalid input syntax for type uuid" rather than filtering anything.
--
-- Nothing hit it for eleven milestones because every query on this platform ran WITH a tenant
-- set. The reminder drain is the first that legitimately does not, and it failed on every pass.
--
-- NULLIF makes the predicate mean what the whole design already claims it means: no tenant, no
-- rows. Fail-closed, and no exception.
--
-- Only communication's two policies are corrected here. The same latent fault exists in every
-- other module's migrations and is worth a sweep of its own -- but it is unreachable there today
-- (no other module queries without a tenant), and quietly rewriting a dozen security policies
-- from inside a notifications change is not a trade worth making.
DROP POLICY notification_template_tenant_isolation ON communication.notification_template;
CREATE POLICY notification_template_tenant_isolation ON communication.notification_template
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

DROP POLICY notification_dispatch_tenant_isolation ON communication.notification_dispatch;
CREATE POLICY notification_dispatch_tenant_isolation ON communication.notification_dispatch
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- ============================================================================================
-- 2. THE DRAIN NEEDS TO SEE ACROSS TENANTS, AND MUST NOT BE ABLE TO READ ACROSS THEM
-- ============================================================================================
-- With the predicate fixed, the drain now correctly sees ZERO rows: it runs on a schedule with no
-- request and no tenant, over a queue a cross-tenant pg_cron sweep produced. Fail-closed is the
-- right behaviour and the drain is the wrong query.
--
-- This function is the narrow, auditable hole. SECURITY DEFINER, so it runs as the owning role
-- and bypasses RLS -- the same standing the three sweeps already have -- but it returns ONLY
-- (dispatch_id, tenant_id) and never message content, a recipient or a phone number. The
-- dispatcher takes each pair, sets the tenant context from it, and does everything else through
-- the ordinary RLS-protected path.
--
-- So the cross-tenant surface is two uuids per queued reminder, rather than app_role being able
-- to read every tenant's message history.
CREATE OR REPLACE FUNCTION communication.pending_reminders()
RETURNS TABLE (dispatch_id UUID, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT d.dispatch_id, d.tenant_id
      FROM communication.notification_dispatch d
     WHERE d.status = 'PENDING'
     ORDER BY d.created_at
     LIMIT 500;
$$;

-- Postgres grants EXECUTE on a new function to PUBLIC by default. app_role genuinely needs this
-- one -- the drain runs as app_role -- so it is granted explicitly after revoking the default,
-- which keeps the grant deliberate and greppable rather than inherited.
REVOKE EXECUTE ON FUNCTION communication.pending_reminders() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION communication.pending_reminders() TO app_role;

COMMENT ON FUNCTION communication.pending_reminders() IS
    'Queued reminder ids across all tenants, for OfferReminderDispatcher. Returns ids only, never message content: the dispatcher sets the tenant context per row and reads the rest under RLS.';
