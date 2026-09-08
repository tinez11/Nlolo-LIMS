-- db-migrations/policy/V10__one_policy_per_underwriting_case.sql
-- One application, one contract.
--
-- V4 added underwriting_case_id as a plain nullable column with no FK and no uniqueness, and
-- PolicyRepository had no finder for it at all -- so nothing on this platform could answer
-- "has this case already been issued?". Both issuance paths reach the same
-- PolicyApiImpl.issuePolicy: the UnderwritingDecisionMade listener, and
-- POST /policies/manual-issue. Neither checked, so one case could produce unlimited policies,
-- each with its own policy number, each billed, each accruing commission, each ceded to the
-- reinsurer and each counted as new business in the regulatory return.
--
-- This is not an exotic edge case. UnderwritingDecisionEventListener's own error handling
-- documents manual issue as the retry for a failed automatic issuance, so a transient failure
-- puts an operator on exactly the path that produces the duplicate -- and if the first
-- issuance had in fact succeeded, they had no way to find out.
--
-- PARTIAL, on two counts, and both are real rather than convenient:
--
--   * Pre-M6 policies have NULL here. V4's own comment records that there is no source of
--     truth to backfill an originating case from, so a total unique index would be
--     unbuildable without inventing data.
--   * Group schemes (V9) pass null deliberately. A scheme is one contract over many lives
--     underwritten separately, not one underwriting case, and it must stay issuable.
--
-- The service checks first and raises PolicyAlreadyIssuedForCaseException naming the existing
-- policy number, because a bare constraint violation surfaces as a 500 with a Postgres string
-- in it, and the operator who trips this needs to be told where the policy they are looking
-- for already is.
CREATE UNIQUE INDEX ux_policy_underwriting_case
    ON policy.policy (tenant_id, underwriting_case_id)
    WHERE underwriting_case_id IS NOT NULL;

COMMENT ON INDEX policy.ux_policy_underwriting_case IS
    'One policy per underwriting case. Partial: pre-M6 policies and group schemes carry NULL.';
