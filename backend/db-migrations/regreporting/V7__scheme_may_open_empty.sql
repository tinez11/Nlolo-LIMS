-- db-migrations/regreporting/V7__scheme_may_open_empty.sql
-- A scheme may be activated with a total of ZERO: the credit-life set-up form asks for no opening borrower -- they
-- arrive with the lender's first monthly file. V2's CHECK (> 0) dated from when every scheme opened with its member
-- schedule, so the activation's dimension row was refused, and with no dimension every borrower a lender later sent
-- was dropped as an unattributed member movement: missing from the return, not merely late.
--
-- Zero is allowed only as a starting point. PolicyDimension.restateSumAssured still refuses to restate a scheme to
-- zero -- the last member leaving belongs to the close event (see that method).

ALTER TABLE regreporting.policy_dimension DROP CONSTRAINT policy_dimension_sum_assured_positive;
ALTER TABLE regreporting.policy_dimension
    ADD CONSTRAINT policy_dimension_sum_assured_non_negative CHECK (sum_assured_amount >= 0);

-- The schemes the old CHECK lost, from policy's own record where that schema is present (a module test may apply
-- regreporting without it). Their dimension takes the scheme's CURRENT total, so a later member event measures its
-- delta from the right place. What cannot be recovered is the movements the lost activation and any dropped member
-- events would have written -- a quarter already reported is not restated by a migration.
DO $$
BEGIN
    IF to_regclass('policy.policy') IS NULL THEN
        RETURN;
    END IF;
    INSERT INTO regreporting.policy_dimension (tenant_id, policy_number, product_id, sum_assured_amount,
                                               sum_assured_currency, issue_date)
    SELECT p.tenant_id, p.policy_number, p.product_id, p.sum_assured_amount, p.sum_assured_currency, p.issue_date
      FROM policy.policy p
     WHERE p.product_category IN ('GROUP_LIFE', 'CREDIT_LIFE')
       AND p.status NOT IN ('PROPOSED', 'NOT_TAKEN_UP', 'CANCELLED_FREE_LOOK')
       AND p.issue_date IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM regreporting.policy_dimension d
                        WHERE d.tenant_id = p.tenant_id AND d.policy_number = p.policy_number);
END;
$$;
