-- Selection helper for CoverExpiryDrain (product step 0, D2). Answers "which in-force policies
-- have run out their term and pay nothing at the end", across every tenant, for a Java drain that
-- then expires each under its own tenant context.
--
-- A Java drain rather than a pg_cron UPDATE, and this is the same reasoning communication's
-- pending_reminders() / OfferReminderDispatcher follow: the transition has CONSUMERS. Expiry
-- publishes policy.PolicyExpired, which billing consumes to terminate the schedule and audit
-- records; a raw SQL UPDATE would change the status in the silence policy.sweep_expired_offers()
-- is on record regretting. So SQL only SELECTS the work; Java raises the event.
--
-- SECURITY DEFINER and cross-tenant, exactly as pending_reminders() and the sweeps are: it runs
-- on a schedule with no request and therefore no tenant, so it must see past RLS. It returns
-- ONLY (policy_number, tenant_id) -- never a party, a sum assured or any business figure -- and
-- the drain sets the tenant from each row and reads everything else under ordinary RLS.
--
-- WHAT IT EXCLUDES, and why each:
--   * PROPOSED / NOT_TAKEN_UP / SURRENDERED / MATURED / EXPIRED -- not on risk, or already closed.
--     Only ACTIVE, REINSTATED and SUSPENDED can expire (mirrors Policy.canExpire).
--   * a policy with an ACTIVE MATURITY coverage row -- it matures (pays), it does not expire.
--     Automatic maturity is a later step; until then a person files the maturity claim, and this
--     must not race them by closing the policy first.
CREATE OR REPLACE FUNCTION policy.policies_due_to_expire()
RETURNS TABLE (policy_number VARCHAR, tenant_id UUID)
LANGUAGE sql SECURITY DEFINER AS $$
    SELECT p.policy_number, p.tenant_id
      FROM policy.policy p
     WHERE p.status IN ('ACTIVE','REINSTATED','SUSPENDED')
       AND p.maturity_date IS NOT NULL
       AND p.maturity_date <= current_date
       AND NOT EXISTS (
           SELECT 1 FROM policy.coverage c
            WHERE c.policy_number = p.policy_number
              AND c.benefit_type = 'MATURITY'
              AND c.active)
     ORDER BY p.maturity_date
     LIMIT 500;
$$;

-- Postgres grants EXECUTE to PUBLIC by default; app_role genuinely needs this one (the drain runs
-- as app_role), so it is revoked and re-granted deliberately -- the same shape as
-- communication.pending_reminders().
REVOKE EXECUTE ON FUNCTION policy.policies_due_to_expire() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION policy.policies_due_to_expire() TO app_role;

COMMENT ON FUNCTION policy.policies_due_to_expire() IS
    'In-force termed policies past their maturity date that carry no maturity benefit, across all tenants, for CoverExpiryDrain. Ids only: the drain sets the tenant per row and expires each under RLS.';
