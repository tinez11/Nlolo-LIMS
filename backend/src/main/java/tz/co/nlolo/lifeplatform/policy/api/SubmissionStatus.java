package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Where a lender's submitted schedule stands.
 *
 * <p>Only ACCEPTED puts anybody on risk. A PENDING submission has been read and judged
 * and has enrolled nobody, which is the whole point of separating the two.
 */
public enum SubmissionStatus {

    /** Read, judged, awaiting a second pair of eyes. Nobody is covered yet. */
    PENDING,

    /** A second staff user turned it into cover. */
    ACCEPTED,

    /**
     * Abandoned without enrolling anybody -- a file sent in error, or one whose
     * rejections are numerous enough that the lender would rather correct and resend
     * than accept the good half.
     */
    WITHDRAWN
}
