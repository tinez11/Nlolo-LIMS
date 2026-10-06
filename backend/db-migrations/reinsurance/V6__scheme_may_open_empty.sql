-- db-migrations/reinsurance/V6__scheme_may_open_empty.sql
-- A scheme may be activated with a total of ZERO: the credit-life set-up form asks for no opening borrower -- they
-- arrive with the lender's first monthly file. V2's CHECK (> 0) refused the projection row, so the activation failed
-- and a later claim on the scheme read as a "pre-M8 policy" rather than as a scheme. A scheme is never ceded (client
-- decision 2026-09-22); the row exists so that this module knows it is one.

ALTER TABLE reinsurance.policy_projection DROP CONSTRAINT policy_projection_sum_assured_amount_check;
ALTER TABLE reinsurance.policy_projection
    ADD CONSTRAINT policy_projection_sum_assured_non_negative CHECK (sum_assured_amount >= 0);

-- The schemes the old CHECK lost, from policy's own record where that schema is present (a module test may apply
-- reinsurance without it). No cover period: a scheme is never on a bordereau.
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
     WHERE p.product_category IN ('GROUP_LIFE', 'CREDIT_LIFE')
       AND p.status NOT IN ('PROPOSED', 'NOT_TAKEN_UP', 'CANCELLED_FREE_LOOK')
       AND p.issue_date IS NOT NULL
       AND p.premium_amount > 0
       AND NOT EXISTS (SELECT 1 FROM reinsurance.policy_projection r
                        WHERE r.tenant_id = p.tenant_id AND r.policy_number = p.policy_number);
END;
$$;
