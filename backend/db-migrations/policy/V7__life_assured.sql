-- The policy records who is insured, not only who owns the contract.
--
-- policy.policy has carried policyholder_party_id alone. On most life business those are
-- two different people, and the contract has to say which is which -- a death claim is
-- assessed against the LIFE ASSURED, while the policyholder is who pays and who holds
-- the rights.
--
-- Companion to underwriting/V4__proposal_identity.sql; see that file for why the
-- backfill below is copying a known fact rather than inventing one.
--
-- See docs/superpowers/specs/2026-09-03-build4b-proposal-identity-design.md.

ALTER TABLE policy.policy
    ADD COLUMN life_assured_party_id UUID;

-- Same reasoning as the underwriting backfill: the old model had one party slot per
-- policy and it was the life the premium was rated on, so copying it forward states what
-- these rows already meant. Build 1 and Build 2 refused to backfill because those columns
-- recorded facts nobody had; this one was recorded, just not separately.
UPDATE policy.policy
   SET life_assured_party_id = policyholder_party_id
 WHERE life_assured_party_id IS NULL;

-- "Every policy on this person's life" -- distinct from "every policy this person owns",
-- and the question a claims assessor actually asks. The existing beneficiary index
-- answers a third, unrelated question.
CREATE INDEX idx_policy_life_assured
    ON policy.policy (tenant_id, life_assured_party_id)
    WHERE life_assured_party_id IS NOT NULL;
