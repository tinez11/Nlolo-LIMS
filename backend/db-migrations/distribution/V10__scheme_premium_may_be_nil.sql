-- db-migrations/distribution/V10__scheme_premium_may_be_nil.sql
-- A credit-life scheme's contract carries a nil premium (policy V44, 2026-11-02): each accepted file is invoiced on
-- the lender's yearly agreement, so the set-up no longer asks for a figure. V2's CHECK (> 0) refused this module's
-- projection row, so the scheme's activation was lost here -- and with it the agent of record every file's commission
-- is paid to. Zero or more, as reinsurance's sum assured became for the same scheme (reinsurance V6).
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE distribution.policy_projection DROP CONSTRAINT IF EXISTS policy_projection_premium_amount_check;
ALTER TABLE distribution.policy_projection
    ADD CONSTRAINT policy_projection_premium_amount_check CHECK (premium_amount >= 0);

-- The schemes the old CHECK lost, from policy's own record where that schema is present (a module test may apply
-- distribution without it).
DO $$
BEGIN
    IF to_regclass('policy.policy') IS NULL THEN
        RETURN;
    END IF;
    INSERT INTO distribution.policy_projection (tenant_id, policy_number, agent_id, product_id, premium_amount,
                                                premium_currency, issue_date, policyholder_party_id)
    SELECT p.tenant_id, p.policy_number, p.agent_of_record_id, p.product_id, p.premium_amount, p.premium_currency,
           p.issue_date, p.policyholder_party_id
      FROM policy.policy p
     WHERE p.product_category = 'CREDIT_LIFE'
       AND p.premium_amount = 0
       AND p.status NOT IN ('PROPOSED', 'NOT_TAKEN_UP', 'CANCELLED_FREE_LOOK')
       AND p.issue_date IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM distribution.policy_projection d
                        WHERE d.tenant_id = p.tenant_id AND d.policy_number = p.policy_number);
END;
$$;
