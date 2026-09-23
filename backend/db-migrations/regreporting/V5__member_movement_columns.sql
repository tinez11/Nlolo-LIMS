-- Membership change is a movement, and until now it was not recorded anywhere.
--
-- A group scheme's sum assured is the total of its member schedule. policy_dimension captures
-- that total once, at activation, and nothing has ever updated it: policy.GroupMemberAdded and
-- policy.GroupMemberExited had no consumer at all. On an employer scheme that is occasional
-- drift. On credit life, members are added every month in files of several hundred and exited on
-- every settled claim, so SUM_ASSURED_IN_FORCE drifts continuously and in both directions
-- (design spec 2.14).
--
-- No return is filed from it YET: the only return definition on the platform is the placeholder
-- V2 seeds below its own line definitions, whose description says its line codes are invented and
-- which exists under one hardcoded tenant, pending the TIRA catalogue (C2). That is not a reason
-- to defer this. These movement rows are the permanent record; movement never captured cannot be
-- reconstructed afterwards from anything, whatever the final return turns out to ask for.
--
-- TWO NEW COLUMNS RATHER THAN REUSING sum_assured_issued, and this is a decision, not caution.
-- That column feeds two metrics: SUM_ASSURED_IN_FORCE (a STOCK figure, issued minus terminated,
-- which is the one that is wrong) and NEW_BUSINESS_SUM_ASSURED (a FLOW figure, issued alone).
-- Merging member cover into it would destroy a distinction that cannot be reconstructed: no
-- query could then separate cover from newly written schemes from cover from new members on
-- existing schemes, because the stored rows would no longer carry the difference. Kept apart,
-- either definition stays computable, and whichever the real TIRA catalogue asks for becomes a
-- reporting decision rather than a migration.
--
-- Note for whoever owns C2, recorded here because it surfaced while writing this and is NOT
-- fixed by it: the placeholder's PL-04 line, "New business sum assured in period", counts policy
-- activations only. A scheme that opens with one borrower and enrols five hundred over the year
-- reports that one borrower's cover. That understates new business on ANY scheme that grows
-- after activation; credit life only makes it loud.
ALTER TABLE regreporting.policy_movement
    ADD COLUMN sum_assured_member_added NUMERIC(19,2) NOT NULL DEFAULT 0,
    ADD COLUMN sum_assured_member_exited NUMERIC(19,2) NOT NULL DEFAULT 0;

-- Folded into the EXISTING constraint rather than added as a second one, so there is one place
-- that says what a movement measure may hold. Every measure here is a GROSS non-negative figure
-- and the sign lives in which column is incremented -- see V2's own note on why this is >= 0 and
-- not > 0: an upsert creates the row with zeros and then increments exactly one column, so zero
-- is the normal value for every other cause in that period.
ALTER TABLE regreporting.policy_movement
    DROP CONSTRAINT policy_movement_non_negative;

ALTER TABLE regreporting.policy_movement
    ADD CONSTRAINT policy_movement_non_negative CHECK (
        policies_issued >= 0 AND policies_reinstated >= 0 AND policies_lapsed >= 0
        AND policies_matured >= 0 AND policies_claim_terminated >= 0
        AND sum_assured_issued >= 0 AND sum_assured_terminated >= 0
        AND sum_assured_member_added >= 0 AND sum_assured_member_exited >= 0);

COMMENT ON COLUMN regreporting.policy_movement.sum_assured_member_added IS
    'Cover added to a group scheme by members joining after activation. Raises '
    'SUM_ASSURED_IN_FORCE; deliberately NOT part of NEW_BUSINESS_SUM_ASSURED -- see this '
    'migration''s header.';
COMMENT ON COLUMN regreporting.policy_movement.sum_assured_member_exited IS
    'Cover removed from a group scheme by members leaving. Lowers SUM_ASSURED_IN_FORCE. Does '
    'NOT include the final close-out when the LAST member leaves: that restates the scheme to '
    'zero and closes it, and policy.PolicySurrendered lands it in sum_assured_terminated.';
