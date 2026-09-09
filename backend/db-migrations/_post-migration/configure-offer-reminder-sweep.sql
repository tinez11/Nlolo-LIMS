-- Run by the CD pipeline immediately after Flyway migrations complete, same convention as
-- configure-billing-sweep.sql and configure-offer-expiry-sweep.sql. scripts/migrate.sh
-- deliberately does not apply anything in _post-migration.
--
-- Queues a reminder for every offer about to close.
--
-- WHY THIS IS SQL AND NOT A SPRING @Scheduled, which is the opposite of the usual answer for a
-- queue drain: the selection needs policy.policy, and `communication` may not read it. Its
-- allowedDependencies are party::api and refdata::api, and NoCrossModuleJoinTest fails the build
-- on a cross-module join. A pg_cron function is an ops-level object owned by the migration role,
-- outside the module graph entirely -- the same standing this platform already gives
-- billing.sweep_billing_state(), which reads policy.policy for exactly the same kind of reason.
--
-- WHAT IT DOES NOT DO: send. SQL cannot call an SMS aggregator. It inserts PENDING dispatch rows
-- and OfferReminderDispatcher (a Spring @Scheduled inside communication) sends them. That split
-- is the honest one: selection needs a cross-schema read and central single execution, delivery
-- needs an HTTP client and a template renderer, and neither half can do the other's job.
--
-- SECURITY DEFINER and cross-tenant, matching the other two sweeps: a business-state sweep runs
-- over every tenant and cannot rely on a request-scoped TenantContext.
--
-- REVOKE for the reason billing's own header records at length: Postgres grants EXECUTE on a new
-- function to PUBLIC by default, and without this app_role could queue reminders across every
-- tenant. That gap sat unnoticed in billing.sweep_billing_state() from M4 to M7.
CREATE OR REPLACE FUNCTION communication.sweep_offer_reminders() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    validity_days INTEGER;
    reminder_days INTEGER;
BEGIN
    SELECT value::INTEGER INTO validity_days
      FROM refdata.reference_code_set
     WHERE code_set_key = 'TZ_OFFER_VALIDITY_DAYS' AND code = 'DEFAULT';
    SELECT value::INTEGER INTO reminder_days
      FROM refdata.reference_code_set
     WHERE code_set_key = 'TZ_OFFER_REMINDER_DAYS' AND code = 'DEFAULT';

    -- Warn and queue nothing rather than inventing a window. A reminder sent on a date nobody
    -- approved is worse than a reminder not sent: it tells a customer a deadline that is not
    -- their deadline.
    IF validity_days IS NULL OR reminder_days IS NULL THEN
        RAISE WARNING 'TZ_OFFER_VALIDITY_DAYS or TZ_OFFER_REMINDER_DAYS is not seeded; no reminders queued';
        RETURN;
    END IF;

    INSERT INTO communication.notification_dispatch
        (tenant_id, party_id, template_key, channel, status, policy_number)
    SELECT p.tenant_id,
           p.policyholder_party_id,
           'OFFER_CLOSING',
           c.channel,
           'PENDING',
           p.policy_number
      FROM policy.policy p
      JOIN party.party pa
        ON pa.party_id = p.policyholder_party_id
       AND pa.tenant_id = p.tenant_id
      -- One row per channel the customer can actually be reached on. Email is additional, not
      -- alternative, matching NotificationApiImpl: an address is a second chance at telling
      -- somebody their cover has not started.
      CROSS JOIN LATERAL (
          SELECT 'SMS' AS channel WHERE pa.phone_number IS NOT NULL AND pa.phone_number <> ''
          UNION ALL
          SELECT 'EMAIL' WHERE pa.email IS NOT NULL AND pa.email <> ''
      ) c
     WHERE p.status = 'PROPOSED'
       -- Inside the reminder window: the offer closes within reminder_days, and has not closed
       -- already. The second half matters -- without it every offer past its deadline that the
       -- expiry sweep has not yet reached would be reminded about a date in the past.
       AND p.created_at + (validity_days || ' days')::INTERVAL
             <= now() + (reminder_days || ' days')::INTERVAL
       AND p.created_at + (validity_days || ' days')::INTERVAL > now()
       -- Told once. The guard against a daily sweep reminding the same customer every day for a
       -- week, which would teach them to ignore this platform's messages entirely.
       AND NOT EXISTS (
           SELECT 1 FROM communication.notification_dispatch d
            WHERE d.tenant_id = p.tenant_id
              AND d.policy_number = p.policy_number
              AND d.template_key = 'OFFER_CLOSING'
              AND d.channel = c.channel
       );
END;
$$;

REVOKE EXECUTE ON FUNCTION communication.sweep_offer_reminders() FROM PUBLIC;

-- Daily at 03:00, an hour after the expiry sweep. Order matters on the one day they overlap: an
-- offer that closed overnight should be expired and told so, not reminded about a deadline it
-- has already missed. The window predicate above enforces that independently, and the hour's gap
-- means the two are not also contending for the same rows.
SELECT cron.schedule('offer-reminder-sweep', '0 3 * * *', $$SELECT communication.sweep_offer_reminders()$$);
