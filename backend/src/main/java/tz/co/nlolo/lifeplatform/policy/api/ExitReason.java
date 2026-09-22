package tz.co.nlolo.lifeplatform.policy.api;

/**
 * Why a member stopped being covered.
 *
 * <p>Until credit life there was only ever one reason — the insurer paid a claim and the life
 * left — so no column recorded it. A loan can end four other ways, and the difference is not
 * bookkeeping: a refund and a commission clawback both turn on this value, and paying a claim
 * and repaying a loan must not be treated the same way.
 */
public enum ExitReason {

    /** The borrower repaid the loan ahead of its term. The commonest reason by far. */
    SETTLED_EARLY,

    /**
     * The loan was replaced by a new one.
     *
     * <p>Always exit-and-re-enrol, never an in-place amendment: a restructured loan is a
     * different risk over a different term. The new loan is a new row with a blank
     * {@code member_reference} and earns its own; this one keeps the reference it had.
     */
    REFINANCED,

    /** The lender gave up on recovery. Cover ended, so the unexpired premium still goes back. */
    WRITTEN_OFF,

    /** The loan was cancelled before it ran — disbursed in error, or withdrawn by the borrower. */
    CANCELLED,

    /**
     * The insurer paid out and the cover was used.
     *
     * <p>The one reason that refunds NOTHING: the premium was fully earned the moment the claim
     * was paid, and refunding here would pay the claim and give back the premium that funded
     * it. Written by {@code dischargeForSettledClaim}, never by a lender's exits file.
     */
    CLAIM_SETTLED
}
