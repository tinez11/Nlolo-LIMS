-- Free-look cancellations, as the regulatory return has to show them (product step 2).
--
-- A free-look cancellation is the one termination-shaped event that is NOT a termination. The
-- customer exercised a statutory right inside the cooling-off window and the contract is void FROM
-- INCEPTION, so in law it was never bought.
--
-- WHY NOT policies_lapsed OR policies_matured. Both add to sum_assured_terminated, which would
-- report the same contract as BOTH written and terminated -- inflating gross new business and
-- gross terminations at once, and poisoning persistency: a 13-month persistency ratio would be
-- computed over policies that never existed. The treatment is to REVERSE the issuance instead, so
-- the cohort simply does not contain it.
--
-- WHY A VISIBLE COUNTER AND NOT A SILENT REVERSAL. Backing the issuance out alone makes a quarter's
-- new business shrink with nothing to explain it, and an actuary reconciling the reported figure
-- against the policies actually issued would find a gap and no cause. This column is that cause,
-- and it makes cooling-off volume reportable in its own right.
--
-- THE PERIOD IS THE ISSUANCE COHORT'S, not the cancellation's. handlePolicyActivated keys its
-- movement on quarterOfDate(issueDate) and stores that date on policy_dimension, so the cohort is
-- recoverable exactly -- and deriving it from stored data rather than from the event keeps the
-- module's replay guarantee intact (see ProjectionSupport's header on why a period must never come
-- from "today"). A window of up to 365 days means a cancellation can fall in a later quarter than
-- the issuance and restate it; that is inherent to cohort accounting and is how cooling-off is
-- normally handled.
ALTER TABLE regreporting.policy_movement
    ADD COLUMN policies_cancelled_free_look INTEGER NOT NULL DEFAULT 0;

-- Folded into the EXISTING constraint, the shape V5 established, so there is one place that says
-- what a movement measure may hold.
--
-- The >= 0 bound on policies_issued and sum_assured_issued is what makes the REVERSAL safe. It can
-- never fire legitimately: every cancellation is preceded by an activation that incremented the
-- same (tenant, period, product_id) row, because both derive the period from the same issue date.
-- What it does catch is a REDELIVERED event -- this module has no de-duplication and its listeners
-- are AFTER_COMMIT with at-least-once delivery -- turning a double decrement into a loud rollback
-- rather than a silent understatement. That is strictly better than the lapsed and matured
-- handlers, which double-count a redelivery in silence.
ALTER TABLE regreporting.policy_movement
    DROP CONSTRAINT policy_movement_non_negative;

ALTER TABLE regreporting.policy_movement
    ADD CONSTRAINT policy_movement_non_negative CHECK (
        policies_issued >= 0 AND policies_reinstated >= 0 AND policies_lapsed >= 0
        AND policies_matured >= 0 AND policies_claim_terminated >= 0
        AND policies_cancelled_free_look >= 0
        AND sum_assured_issued >= 0 AND sum_assured_terminated >= 0
        AND sum_assured_member_added >= 0 AND sum_assured_member_exited >= 0);

COMMENT ON COLUMN regreporting.policy_movement.policies_cancelled_free_look IS
    'Policies the customer cancelled inside the free-look window, counted in the quarter they were '
    'ISSUED in. The matching issuance is reversed out of policies_issued and sum_assured_issued, so '
    'these are excluded from new business rather than shown as terminations -- the contract is void '
    'from inception. Deliberately NOT part of sum_assured_terminated.';
