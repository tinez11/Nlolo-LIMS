-- Every case a policy was issued from has its sale locked (2026-10-08).
--
-- sale_locked_at (V18, 2026-10-06) is set when a policy is issued from the case, and the manual-issue
-- screen now lists only decided cases with no lock -- the ones no policy has come from yet. Policies
-- issued before V18 left their cases unlocked (846 on the dev database), so they would reappear on that list as if
-- still waiting. Locked here at the moment their policy was created.
--
-- Tolerant: a database (or test) without the policy schema has no issued policies to backfill.
DO $$
BEGIN
    IF to_regclass('policy.policy') IS NOT NULL THEN
        UPDATE underwriting.underwriting_case c
           SET sale_locked_at = p.created_at
          FROM policy.policy p
         WHERE p.underwriting_case_id = c.case_id
           AND p.tenant_id = c.tenant_id
           AND c.sale_locked_at IS NULL;
    END IF;
END $$;
