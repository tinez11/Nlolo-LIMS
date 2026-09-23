package tz.co.nlolo.lifeplatform.payment.api;

/**
 * IN_DOUBT (review finding C2) is NON-terminal and means "sent to the rail, outcome unknown" --
 * distinct from FAILED, which means the rail definitely did not pay. See
 * {@code payment.domain.DisbursementInstruction}'s javadoc and db-migrations/payment/V4 section 1.
 * Adding it here is not optional decoration: {@code PaymentApiImpl.toView} calls
 * {@code DisbursementStatus.valueOf(d.getStatus())}, so a stored status with no enum constant
 * would make the read endpoints throw for exactly the rows an operator most needs to look at.
 */
public enum DisbursementStatus {

    /** Handed to the rail, awaiting its callback. */
    PENDING,

    /**
     * Recorded and posted; waiting for a person to move the money.
     *
     * <p>Deliberately NOT reusing PENDING. A payout sitting on a finance officer desk must be
     * distinguishable from one the gateway is already working -- otherwise nobody chases it,
     * because it looks in flight.
     */
    AWAITING_EXECUTION,

    IN_DOUBT, COMPLETED, FAILED
}
