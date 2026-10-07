-- Group funeral schemes (2026-10-07): a covered life on a scheme belongs to one member's family. Null on an individual
-- funeral policy, whose lives are the policy's own family.
--
-- Idempotent and tolerant of a database without covered_life (V35): a test database may apply it in a list that
-- reaches V35 only later, and then again after V35.
DO $$
BEGIN
    IF to_regclass('policy.covered_life') IS NOT NULL THEN
        ALTER TABLE policy.covered_life
            ADD COLUMN IF NOT EXISTS policy_member_id UUID REFERENCES policy.policy_member (policy_member_id);
        CREATE INDEX IF NOT EXISTS idx_covered_life_member
            ON policy.covered_life (tenant_id, policy_number, policy_member_id);
        -- A scheme's main member is named on the association's schedule, not a registered party: the scheme's
        -- policyholder is the association. An individual policy's main member is still always a party.
        ALTER TABLE policy.covered_life DROP CONSTRAINT IF EXISTS covered_life_check2;
        ALTER TABLE policy.covered_life DROP CONSTRAINT IF EXISTS covered_life_main_member_identified;
        ALTER TABLE policy.covered_life ADD CONSTRAINT covered_life_main_member_identified
            CHECK (role <> 'MAIN_MEMBER' OR party_id IS NOT NULL OR policy_member_id IS NOT NULL);
        -- A life may carry no premium of its own: a dependant priced at nil on an individual plan (product V29),
        -- and every scheme life, whose association pays per member rather than per life.
        ALTER TABLE policy.covered_life DROP CONSTRAINT IF EXISTS covered_life_yearly_premium_check;
        ALTER TABLE policy.covered_life ADD CONSTRAINT covered_life_yearly_premium_check CHECK (yearly_premium >= 0);
        -- One active main member and one active spouse per FAMILY: an individual policy is one family, a scheme
        -- has one per member. The nil uuid stands for "the policy's own family" (no member).
        DROP INDEX IF EXISTS policy.ux_covered_life_one_main;
        DROP INDEX IF EXISTS policy.ux_covered_life_one_spouse;
        CREATE UNIQUE INDEX ux_covered_life_one_main ON policy.covered_life
            (policy_number, COALESCE(policy_member_id, '00000000-0000-0000-0000-000000000000'::uuid))
            WHERE status = 'ACTIVE' AND role = 'MAIN_MEMBER';
        CREATE UNIQUE INDEX ux_covered_life_one_spouse ON policy.covered_life
            (policy_number, COALESCE(policy_member_id, '00000000-0000-0000-0000-000000000000'::uuid))
            WHERE status = 'ACTIVE' AND role = 'SPOUSE';
    END IF;
END $$;
