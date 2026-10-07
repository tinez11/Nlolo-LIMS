-- Group funeral schemes (2026-10-07): the expense allocation's in-force driver counts LIVES, not contracts. One policy
-- covering an association's 40 families and their 150 lives is not one unit of maintenance work.
--
-- Kept from policy's events: policy.PolicyActivated, policy.CoveredLifeAdded / CoveredLifeEnded (funeral policies and
-- group funeral schemes) and policy.GroupMemberAdded / GroupMemberExited (other schemes), each carrying livesCovered --
-- the policy's lives after the change. A row written before this carries 1, as every individual policy does.
ALTER TABLE finaccounting.policy_snapshot ADD COLUMN lives INTEGER NOT NULL DEFAULT 1 CHECK (lives >= 0);
