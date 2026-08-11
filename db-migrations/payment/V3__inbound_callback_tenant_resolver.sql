-- db-migrations/payment/V3__inbound_callback_tenant_resolver.sql
-- Task 8's mobile-money webhook (POST /webhooks/mobile-money-callback) has no tenant when it
-- arrives: it carries no bearer token (authenticated by MobileMoneyHmacFilter's HMAC signature
-- instead), so TenantContextFilter never runs for it and TenantContext is unset for the whole
-- request. The controller must resolve which tenant owns the row it is confirming from the
-- gateway_reference the aggregator's payload carries, THEN set TenantContext for everything
-- after that.
--
-- V2's RLS policies (payment_transaction_tenant_isolation, disbursement_instruction_tenant_
-- isolation, and their partition copies) are `USING (tenant_id = current_setting
-- ('app.current_tenant_id', true)::uuid)`. The `true` argument means "return NULL instead of
-- erroring when unset" -- and `tenant_id = NULL::uuid` evaluates to NULL, never TRUE, for every
-- row. So a plain, ordinary `SELECT ... WHERE gateway_reference = ?` run over app_role's
-- connection BEFORE TenantContext is set is not merely unscoped, it is BLIND: it returns zero
-- rows regardless of whether a matching row exists, because Postgres itself hides every row from
-- app_role's session until app.current_tenant_id names one. There is no default/fallback tenant
-- and no bypass role: app_role is provisioned NOSUPERUSER NOBYPASSRLS
-- (infra/postgres/init/01-create-app-role.sql.template) and stays that way here -- this
-- migration does not touch that role attribute and does not widen any existing RLS policy.
--
-- The mechanism instead is Postgres's own owner exemption. V2 used ENABLE ROW LEVEL SECURITY,
-- not FORCE ROW LEVEL SECURITY, and Postgres exempts a table's OWNER from its own RLS policies
-- unless FORCE is specified. app_role owns no table anywhere in this platform -- every schema is
-- created by the migration-applying role (scripts/migrate.sh's $DB_URL connection; the
-- Testcontainers superuser in tests), which V2 then GRANTs app_role SELECT/INSERT/UPDATE/DELETE
-- on, never ownership. A SECURITY DEFINER function, owned by that same migration role, executes
-- with the OWNER's privileges regardless of the calling role, so it is exempt from the policy
-- the caller itself would otherwise be blocked by -- without ever granting app_role BYPASSRLS or
-- altering the policies these two functions read through.
--
-- This is a NEW category of privilege for app_role, flagged here deliberately rather than
-- slipped in quietly: db-migrations/_post-migration/configure-billing-sweep.sql's
-- SECURITY DEFINER function is explicit that "app_role itself is never granted EXECUTE on this
-- function and never bypasses RLS at any point -- this is infrastructure automation, not a
-- request-path privilege escalation." That held because no legitimate REQUEST-PATH cross-tenant
-- need had arisen yet. This webhook is the first one: an external, HMAC-authenticated caller
-- that genuinely cannot supply a tenant because none has been established yet. The blast radius
-- is kept as narrow as the brief's own framing demands ("a primary-key fetch of a UUID ... not
-- an enumeration surface ... do not widen this into a general-purpose cross-tenant finder"):
-- each function takes exactly the gateway_reference the HMAC-verified caller supplied and
-- returns ONLY the resolved tenant_id, never a row, never any other column. Every subsequent
-- read/write in the request runs through the ordinary tenant-scoped, RLS-enforced repository
-- methods once TenantContext is set from this result -- this function is a bootstrap, not a
-- replacement for RLS.
--
-- Reviewer note: this is a genuinely new security posture for app_role and deserves explicit
-- sign-off beyond this migration's own review, not a rubber stamp because it's "just a helper
-- function."
CREATE FUNCTION payment.resolve_disbursement_tenant(p_gateway_reference text)
RETURNS uuid
LANGUAGE sql
SECURITY DEFINER
SET search_path = payment, pg_temp
AS $$
    SELECT tenant_id FROM payment.disbursement_instruction
    WHERE gateway_reference = p_gateway_reference
    LIMIT 1;
$$;

CREATE FUNCTION payment.resolve_payment_transaction_tenant(p_gateway_reference text)
RETURNS uuid
LANGUAGE sql
SECURITY DEFINER
SET search_path = payment, pg_temp
AS $$
    SELECT tenant_id FROM payment.payment_transaction
    WHERE gateway_reference = p_gateway_reference
    LIMIT 1;
$$;

-- Defence in depth: SECURITY DEFINER functions default to callable by PUBLIC. Revoke that first,
-- then grant EXECUTE to app_role only -- the one role that genuinely needs it, and no other.
REVOKE ALL ON FUNCTION payment.resolve_disbursement_tenant(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION payment.resolve_payment_transaction_tenant(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION payment.resolve_disbursement_tenant(text) TO app_role;
GRANT EXECUTE ON FUNCTION payment.resolve_payment_transaction_tenant(text) TO app_role;
