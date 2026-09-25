-- A member who has DIED but whose claim has not been PAID.
--
-- Between those two moments the member is ACTIVE -- rightly: the spec keeps them on cover until
-- the claim settles, and then backdates the exit to the death (credit-life spec 2.9). But
-- nothing on the member said so, and three things went wrong in that gap:
--
--   * The roll read "ACTIVE, covered for TZS 800,000.00" for a borrower with a death claim
--     approved and a transfer waiting on finance -- indistinguishable from a live loan.
--   * An exits file could take them off cover first. It cannot say CLAIM_SETTLED, so a lender
--     reporting the death picks WRITTEN_OFF or CANCELLED, the member exits with the wrong
--     reason, a premium refund fires that a death never earns, and when the claim later settles
--     its discharge finds them already gone and does nothing -- the wrong record stands.
--   * Policy, which judges exits files, could not see claims at all: claims depends on policy,
--     never the other way round.
--
-- So claims TELLS policy, and policy records it here. Set when a death claim on this member is
-- registered, cleared if it is rejected, set again if it is reopened. Cleared by the settled
-- claim's own exit.
--
-- No backfill from claims.claim. Reading another module's schema from a policy migration would
-- couple the two, and break every test that applies policy's migrations without claims'.

ALTER TABLE policy.policy_member
    ADD COLUMN open_death_claim_id UUID;

-- Only a member still on cover can have a claim in progress. An exit -- by the claim itself or
-- by anything else -- must clear it in the same write, or the row would say two things at once.
ALTER TABLE policy.policy_member
    ADD CONSTRAINT chk_policy_member_open_death_claim_only_while_active CHECK (
        open_death_claim_id IS NULL OR status = 'ACTIVE');
