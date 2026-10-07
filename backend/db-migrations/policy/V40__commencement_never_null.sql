-- Audit 2026-10-07: a policy issued from a case that stated no start date recorded no commencement, and
-- Policy.wasOnRiskOn read that as "on risk on every earlier day" -- a death claim dated before the policy
-- existed passed the on-risk check. Issuance now defaults commencement to the issue date; this backfills
-- the policies issued before it.
--
-- Only rows with no term: a termed policy's maturity is commencement + term
-- (policy_maturity_matches_term), and none without a commencement carried a term when this was written.
-- One with a term would have to have its maturity derived too, so it is left for a person to decide.
UPDATE policy.policy
   SET commencement_date = issue_date
 WHERE commencement_date IS NULL
   AND issue_date IS NOT NULL
   AND policy_term_months IS NULL;
