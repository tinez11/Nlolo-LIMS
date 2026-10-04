-- db-migrations/policy/V33__annuity_ended_status.sql
-- Product step 5 (D1): an annuity that has paid all it ever will -- the last life died with no
-- guarantee left, or the guarantee paid out to the beneficiaries. Terminal. Never EXPIRED, which
-- means cover ran to a term an annuity does not have.
ALTER TABLE policy.policy DROP CONSTRAINT IF EXISTS policy_status_check;
ALTER TABLE policy.policy ADD CONSTRAINT policy_status_check
    CHECK (status IN ('PROPOSED','ACTIVE','LAPSED','SUSPENDED','SURRENDERED','MATURED','REINSTATED',
                      'NOT_TAKEN_UP','EXPIRED','PAID_UP','CANCELLED_FREE_LOOK','ANNUITY_ENDED'));

COMMENT ON COLUMN policy.policy.status IS
    'PROPOSED -> ACTIVE on first premium. Terminal: SURRENDERED, MATURED, EXPIRED, NOT_TAKEN_UP, '
    'CANCELLED_FREE_LOOK, ANNUITY_ENDED. PAID_UP is in force with no premium due. CANCELLED_FREE_LOOK is '
    'void from inception -- wasOnRiskOn is false for every day of it. ANNUITY_ENDED: an annuity owes nothing more.';
