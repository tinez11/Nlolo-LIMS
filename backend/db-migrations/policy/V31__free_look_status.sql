-- db-migrations/policy/V31__free_look_status.sql
-- Product step 2: a policy cancelled inside its free-look window (guide §21.3, "cancelled in the
-- free-look period"). Terminal, and NEVER on risk: cover is void from inception, which is what
-- distinguishes this from a surrender. The customer is treated as never having been insured.
ALTER TABLE policy.policy DROP CONSTRAINT IF EXISTS policy_status_check;
ALTER TABLE policy.policy ADD CONSTRAINT policy_status_check
    CHECK (status IN ('PROPOSED','ACTIVE','LAPSED','SUSPENDED','SURRENDERED','MATURED','REINSTATED',
                      'NOT_TAKEN_UP','EXPIRED','PAID_UP','CANCELLED_FREE_LOOK'));

COMMENT ON COLUMN policy.policy.status IS
    'PROPOSED -> ACTIVE on first premium. Terminal: SURRENDERED, MATURED, EXPIRED, NOT_TAKEN_UP, '
    'CANCELLED_FREE_LOOK. PAID_UP is in force with no premium due. CANCELLED_FREE_LOOK is void '
    'from inception -- wasOnRiskOn is false for every day of it.';
