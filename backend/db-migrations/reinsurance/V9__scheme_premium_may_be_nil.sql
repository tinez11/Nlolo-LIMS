-- db-migrations/reinsurance/V9__scheme_premium_may_be_nil.sql
-- A credit-life scheme's contract carries a nil premium (policy V44, 2026-11-02): each accepted file is invoiced on
-- the lender's yearly agreement. V2's CHECK (> 0) refused the projection row, so the activation was lost and a later
-- claim on the scheme would read as a "pre-M8 policy" rather than as a scheme -- the failure V6 closed for its sum
-- assured. A scheme is never ceded; the row exists so that this module knows it is one.
-- Re-runnable: migrate.sh is not idempotent, so a second run changes nothing.

ALTER TABLE reinsurance.policy_projection DROP CONSTRAINT IF EXISTS policy_projection_premium_amount_check;
ALTER TABLE reinsurance.policy_projection
    ADD CONSTRAINT policy_projection_premium_amount_check CHECK (premium_amount >= 0);

-- The schemes the old CHECK lost, as V6 recovered its own. No cover period: a scheme is never on a bordereau.
DO $$
BEGIN
    IF to_regclass('policy.policy') IS NULL THEN
        RETURN;
    END IF;
    INSERT INTO reinsurance.policy_projection (tenant_id, policy_number, product_id, sum_assured_amount,
                                               sum_assured_currency, premium_amount, premium_currency, issue_date,
                                               product_category, premium_frequency)
    SELECT p.tenant_id, p.policy_number, p.product_id, p.sum_assured_amount, p.sum_assured_currency,
           p.premium_amount, p.premium_currency, p.issue_date, p.product_category, p.premium_frequency
      FROM policy.policy p
     WHERE p.product_category = 'CREDIT_LIFE'
       AND p.premium_amount = 0
       AND p.status NOT IN ('PROPOSED', 'NOT_TAKEN_UP', 'CANCELLED_FREE_LOOK')
       AND p.issue_date IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM reinsurance.policy_projection r
                        WHERE r.tenant_id = p.tenant_id AND r.policy_number = p.policy_number);
END;
$$;
