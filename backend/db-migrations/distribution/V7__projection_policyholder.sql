-- db-migrations/distribution/V7__projection_policyholder.sql
-- Agent commission view (spec 2026-10-12, D1): who holds each policy, so a commission line can name its client.
-- distribution may not call policy (policy depends on distribution::api), so the holder is kept on distribution's own
-- projection, taken from policy.PolicyActivated, which has always carried policyholderPartyId.
-- Re-runnable: migrate.sh is not idempotent, so neither statement fails or rewrites anything on a second run.

ALTER TABLE distribution.policy_projection ADD COLUMN IF NOT EXISTS policyholder_party_id UUID;

-- Backfill from policy's own table. Guarded because a schema built without policy (two distribution-only test classes)
-- has nothing to read; migrate.sh applies policy before distribution, so every real database is backfilled.
DO $$
BEGIN
    IF to_regclass('policy.policy') IS NOT NULL THEN
        UPDATE distribution.policy_projection pp
           SET policyholder_party_id = p.policyholder_party_id
          FROM policy.policy p
         WHERE p.tenant_id = pp.tenant_id
           AND p.policy_number = pp.policy_number
           AND pp.policyholder_party_id IS NULL;
    END IF;
END $$;
