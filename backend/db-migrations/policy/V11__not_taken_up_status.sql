-- db-migrations/policy/V11__not_taken_up_status.sql
-- An offer nobody took up.
--
-- Cover now starts when the first premium clears, so a policy exists in PROPOSED from the
-- moment an underwriter accepts. Most are paid. Some are not, and those need somewhere to end
-- that is not LAPSED -- lapsing is what happens to an IN-FORCE policy whose premiums stop, and
-- calling this that would put contracts which were never on risk into the lapse figures, where
-- they would overstate persistency problems and understate nothing.
--
-- Around 15% of decided cases per the SOA new-business data, so this is an ordinary outcome
-- rather than an error path.
--
-- The constraint is dropped and recreated rather than widened in place, which is the only way
-- Postgres offers. Its name was confirmed against the live catalogue (pg_constraint) rather
-- than assumed from the V1 text.
ALTER TABLE policy.policy DROP CONSTRAINT IF EXISTS policy_status_check;
ALTER TABLE policy.policy ADD CONSTRAINT policy_status_check
    CHECK (status IN ('PROPOSED','ACTIVE','LAPSED','SUSPENDED','SURRENDERED','MATURED','REINSTATED','NOT_TAKEN_UP'));

COMMENT ON COLUMN policy.policy.status IS
    'PROPOSED is an offer awaiting its first premium. NOT_TAKEN_UP is terminal: the offer expired unpaid. Neither is in force.';
