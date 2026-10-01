-- EXPIRED joins the policy status set: a termed policy whose maturity date has passed and which
-- carries no maturity benefit ends by running out, not by maturing (which pays) and not by
-- lapsing (which is unpaid premium). Step 0 of the product-guide work.
--
-- V11's own convention: the CHECK is redefined in full rather than amended, and Policy's own
-- state machine (not this constraint) decides which transitions are legal -- this only bounds the
-- vocabulary. Policy.expire() is the sole writer.

ALTER TABLE policy.policy DROP CONSTRAINT IF EXISTS policy_status_check;
ALTER TABLE policy.policy ADD CONSTRAINT policy_status_check
    CHECK (status IN ('PROPOSED','ACTIVE','LAPSED','SUSPENDED','SURRENDERED','MATURED','REINSTATED','NOT_TAKEN_UP','EXPIRED'));

COMMENT ON COLUMN policy.policy.status IS
    'PROPOSED is an offer awaiting its first premium. NOT_TAKEN_UP is terminal: the offer expired unpaid. EXPIRED is terminal: a termed policy ran its full term and paid nothing, as term cover does. Neither PROPOSED, NOT_TAKEN_UP nor EXPIRED is in force.';
