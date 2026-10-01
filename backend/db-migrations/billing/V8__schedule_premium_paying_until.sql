-- Billing stops at the end of the contract, not a fixed twelve months after issue (product step 0,
-- D1). The schedule now carries the last date a premium may fall due; the roll-forward drain and
-- generateInvoicesAhead both bound on it. Null means the contract does not term (whole life, an
-- annually renewable scheme) -- billed indefinitely, which is correct for those.

ALTER TABLE billing.billing_schedule ADD COLUMN IF NOT EXISTS premium_paying_until DATE;

-- Backfill the schedules that already exist, from the policy they bill. Guarded on the policy
-- table being present, so a test class that applies billing's migrations without policy's still
-- runs this file -- it simply backfills nothing, and every such schedule keeps a null (no bound),
-- which is the pre-existing behaviour. The paying end mirrors Policy.premiumPayingUntil: the
-- premium-paying term where one was agreed, else the policy term, measured from commencement (else
-- issue date).
DO $$
BEGIN
    IF to_regclass('policy.policy') IS NOT NULL THEN
        UPDATE billing.billing_schedule s
           SET premium_paying_until =
               (COALESCE(p.commencement_date, p.issue_date)
                    + (COALESCE(p.premium_paying_term_months, p.policy_term_months) || ' months')::INTERVAL)::DATE
          FROM policy.policy p
         WHERE p.policy_number = s.policy_number
           AND COALESCE(p.premium_paying_term_months, p.policy_term_months) IS NOT NULL
           AND COALESCE(p.commencement_date, p.issue_date) IS NOT NULL;
    END IF;
END $$;

COMMENT ON COLUMN billing.billing_schedule.premium_paying_until IS
    'Last date a premium may fall due. Null = the contract does not term (billed indefinitely). Set from Policy.premiumPayingUntil on PolicyIssued; the roll-forward drain stops here.';
