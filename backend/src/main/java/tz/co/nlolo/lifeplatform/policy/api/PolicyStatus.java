package tz.co.nlolo.lifeplatform.policy.api;

/**
 * PROPOSED and NOT_TAKEN_UP are both "not in force", and both are ordinary rather than
 * exceptional. A policy sits PROPOSED as an offer from the moment an underwriter accepts until
 * its first premium clears; NOT_TAKEN_UP is where it ends if that never happens.
 *
 * <p>NOT_TAKEN_UP is deliberately not LAPSED. Lapsing is what happens to an in-force policy whose
 * premiums stop, and a contract that was never on risk does not belong in the lapse figures.
 */
public enum PolicyStatus { PROPOSED, ACTIVE, LAPSED, SUSPENDED, SURRENDERED, MATURED, REINSTATED, NOT_TAKEN_UP, EXPIRED }
