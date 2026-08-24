package tz.co.nlolo.lifeplatform.payment.api;

/**
 * IN_DOUBT (review finding C2) is NON-terminal and means "sent to the rail, outcome unknown" --
 * distinct from FAILED, which means the rail definitely did not pay. See
 * {@code payment.domain.DisbursementInstruction}'s javadoc and db-migrations/payment/V4 section 1.
 * Adding it here is not optional decoration: {@code PaymentApiImpl.toView} calls
 * {@code DisbursementStatus.valueOf(d.getStatus())}, so a stored status with no enum constant
 * would make the read endpoints throw for exactly the rows an operator most needs to look at.
 */
public enum DisbursementStatus { PENDING, IN_DOUBT, COMPLETED, FAILED }
