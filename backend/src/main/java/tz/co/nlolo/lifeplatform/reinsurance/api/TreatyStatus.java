package tz.co.nlolo.lifeplatform.reinsurance.api;

/** Matches {@code reinsurance_treaty.status}'s CHECK (reinsurance/V2 section 7). Only ACTIVE
 * treaties are eligible for cession selection. */
public enum TreatyStatus {
    /** Waiting for a second person (2026-11-12, finance finding 170): cedes nothing. */
    PROPOSED,
    ACTIVE,
    EXPIRED,
    /** Rejected, or withdrawn by its proposer: never cedes. */
    REJECTED
}
