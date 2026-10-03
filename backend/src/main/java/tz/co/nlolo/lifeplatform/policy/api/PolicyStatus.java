package tz.co.nlolo.lifeplatform.policy.api;

/**
 * PROPOSED and NOT_TAKEN_UP are both "not in force", and both are ordinary rather than
 * exceptional. A policy sits PROPOSED as an offer from the moment an underwriter accepts until
 * its first premium clears; NOT_TAKEN_UP is where it ends if that never happens.
 *
 * <p>NOT_TAKEN_UP is deliberately not LAPSED. Lapsing is what happens to an in-force policy whose
 * premiums stop, and a contract that was never on risk does not belong in the lapse figures.
 *
 * <p>CANCELLED_FREE_LOOK is likewise not a surrender (product step 2, guide §21.3). A surrender
 * ends cover that genuinely ran; a free-look cancellation voids it from inception, so the policy
 * was never on risk on any day and the premium goes back less what the insurer actually spent.
 */
public enum PolicyStatus {
    PROPOSED, ACTIVE, LAPSED, SUSPENDED, SURRENDERED, MATURED, REINSTATED, NOT_TAKEN_UP, EXPIRED, PAID_UP,
    CANCELLED_FREE_LOOK,
    /** Product step 5: an annuity that owes nothing more. Terminal; never EXPIRED, which means a term ran out. */
    ANNUITY_ENDED
}
