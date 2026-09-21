package tz.co.nlolo.lifeplatform.policy.api;

/**
 * What happened to one line of a lender's schedule.
 *
 * <p>Three values rather than a boolean, because {@code ENROLLED_CAPPED} is neither an
 * acceptance nor a rejection and must not be mistaken for either. A capped borrower IS
 * covered -- up to the free cover limit, with a referral open for the excess -- and
 * treating those rows as rejections is the most plausible way to get this wrong.
 */
public enum RowOutcome {

    /** Covered for the full loan. */
    ENROLLED,

    /**
     * Covered, but only up to the scheme's free cover limit, with an underwriting
     * referral open for the excess. Cover, not a refusal.
     */
    ENROLLED_CAPPED,

    /** Not covered. The reason beside it says so in those words. */
    REJECTED
}
