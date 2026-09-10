-- db-migrations/claims/V5__claim_policy_member.sql
-- Which life died.
--
-- claims.claim has carried policy_number and claimant_party_id since V1, and claimant_party_id
-- is who is FILING -- the widow -- not who died. On individual life that is enough, because the
-- policy names exactly one insured life. On a group scheme it is not: "somebody on GL-000123
-- died" is not a claim anyone can assess, value or pay, and on a 500-life schedule nothing on
-- the claim said which employee it was.
--
-- The consequences were not theoretical. Registration checked policy.sum_assured_amount > 0,
-- which on a scheme is the TOTAL of everybody's cover -- 2.5bn where the dead member was
-- insured for 5m -- and Claim.approve had no upper bound at all. Meanwhile
-- policy_member_benefit.covered_amount, stored and effective-dated for the sole stated purpose
-- of being "the figure a claim pays on the date of event", had no reader anywhere.
--
-- NULLABLE, and it must stay nullable: an individual policy has no member, and a member id
-- against one is a caller who believes that contract has a schedule. The rule is "required iff
-- the policy is a group scheme", which no single-row CHECK can express -- the schedule lives in
-- another schema -- so it is enforced in ClaimsApiImpl via PolicyApi.claimableCover and stated
-- here so the next reader of this table knows the constraint exists somewhere.
--
-- No FOREIGN KEY to policy.policy_member, deliberately, and consistent with claim.policy_number
-- carrying no FK to policy.policy either. Cross-module FKs are not used on this platform: each
-- module owns its own schema, and the reference is validated through PolicyApi at write time.
ALTER TABLE claims.claim
    ADD COLUMN policy_member_id UUID;

COMMENT ON COLUMN claims.claim.policy_member_id IS
    'The insured life this claim is for, on a group scheme. NULL on individual business, where '
    'the policy itself names the life. Validated through PolicyApi at registration; no FK, '
    'because policy owns that table.';

-- "Every claim against this life", which is the question asked when a second claim arrives on
-- somebody already paid out for. Partial: individual claims are the overwhelming majority and
-- all of them are NULL here.
CREATE INDEX idx_claim_policy_member ON claims.claim (tenant_id, policy_member_id)
    WHERE policy_member_id IS NOT NULL;
