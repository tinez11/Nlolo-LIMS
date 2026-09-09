-- Run by the CD pipeline immediately after Flyway migrations complete, same convention as
-- configure-billing-sweep.sql and configure-pg-partman.sql (never as a Flyway migration itself).
-- scripts/migrate.sh deliberately does not apply anything in _post-migration.
--
-- Closes offers nobody took up. A policy sits PROPOSED from the moment an underwriter accepts
-- until its first premium clears. Most are paid; the rest would otherwise sit as open offers
-- forever, on terms whose medical evidence goes stale -- which is the reason offers expire at
-- all. Around 15% of decided cases per the SOA new-business data, so this is an ordinary
-- outcome, not an error path, and the volume is why it is swept rather than handled by hand.
--
-- NOT_TAKEN_UP rather than LAPSED, deliberately. Lapsing is what happens to an IN-FORCE policy
-- whose premiums stop; a contract that was never on risk does not belong in the lapse figures,
-- where it would overstate persistency problems.
--
-- SECURITY DEFINER and cross-tenant, matching billing.sweep_billing_state(): a business-state
-- sweep runs over every tenant and cannot rely on a request-scoped TenantContext. It executes
-- with the privileges of the role that owns it (the migration-applying role, which owns every
-- table in this schema), bypassing RLS by table ownership.
--
-- The REVOKE is not optional and not cosmetic: Postgres grants EXECUTE on a new function to
-- PUBLIC by default -- unlike tables, where a bare CREATE grants nothing -- so without it
-- app_role could call this SECURITY DEFINER function directly and expire offers across every
-- tenant. That exact gap went unnoticed in billing.sweep_billing_state() from M4 to M7; it is
-- closed here from the start. pg_cron records each job's scheduling role in cron.job.username
-- (the migration-applying role, not app_role), so the real caller is unaffected.
CREATE OR REPLACE FUNCTION policy.sweep_expired_offers() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    validity_days INTEGER;
BEGIN
    SELECT value::INTEGER INTO validity_days
      FROM refdata.reference_code_set
     WHERE code_set_key = 'TZ_OFFER_VALIDITY_DAYS' AND code = 'DEFAULT';

    -- Warn and do nothing rather than defaulting to some number picked here. A missing parameter
    -- is a deployment fault, and silently inventing a window would terminate real offers on a
    -- figure no policy committee ever approved.
    IF validity_days IS NULL THEN
        RAISE WARNING 'TZ_OFFER_VALIDITY_DAYS is not seeded; no offers expired';
        RETURN;
    END IF;

    -- WHERE status = 'PROPOSED' is load-bearing. This is a raw UPDATE, so it does NOT pass
    -- through Policy.markNotTakenUp() and does not get that method's guard for free: this
    -- predicate IS the guard, enforcing the same rule at the same moment. Two statements of one
    -- invariant -- if markNotTakenUp() ever gains a condition, it must be added here too. Its
    -- javadoc carries the matching note.
    UPDATE policy.policy
       SET status = 'NOT_TAKEN_UP'
     WHERE status = 'PROPOSED'
       AND created_at < now() - (validity_days || ' days')::INTERVAL;
END;
$$;

REVOKE EXECUTE ON FUNCTION policy.sweep_expired_offers() FROM PUBLIC;

-- Daily at 02:00, not every 15 minutes like the billing sweep. The window is measured in days,
-- so a sweep that runs more often only moves an expiry a few hours earlier while taking a write
-- lock on policy.policy for no benefit.
SELECT cron.schedule('offer-expiry-sweep', '0 2 * * *', $$SELECT policy.sweep_expired_offers()$$);
