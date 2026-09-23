package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Why one row of a lender's exits file could not take a loan off cover.
 *
 * <p>Separate from {@link EnrolmentRejection} rather than shared, because the two files fail
 * for different reasons and a shared enum would carry values that mean nothing on one of them.
 *
 * <p>The consequence of a rejection here runs the OPPOSITE way from enrolment, which is worth
 * holding onto: a rejected enrolment row leaves somebody uninsured, and somebody eventually
 * notices. A rejected exit row leaves a loan still on cover and still being charged for, and
 * nobody notices at all. That is why the report is a deliverable on both files.
 */
public enum ExitRejection {

    /** A column the row cannot do without was blank. */
    MISSING_REQUIRED_FIELD,

    /** A date, or an amount, that could not be read as one. Usually an Excel export fault. */
    MALFORMED_VALUE,

    /**
     * A reason outside the four a lender may state.
     *
     * <p>Covers both nonsense and {@code CLAIM_SETTLED}: the latter is a real
     * {@link ExitReason}, but only the claim path may write it.
     */
    UNKNOWN_EXIT_REASON,

    /** The same member reference appears twice in one file. */
    DUPLICATE_REFERENCE,

    /** No member of this scheme carries that reference. */
    UNKNOWN_MEMBER_REFERENCE,

    /** The loan is already off cover — an earlier file, or a settled claim, took it off. */
    ALREADY_EXITED,

    /** The exit is dated before the loan was disbursed, so it names a cover that never ran. */
    EXIT_BEFORE_COVER_STARTED
}
