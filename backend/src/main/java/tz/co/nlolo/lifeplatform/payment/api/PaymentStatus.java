package tz.co.nlolo.lifeplatform.payment.api;

/** See {@link DisbursementStatus} for why IN_DOUBT exists and why it must appear here rather than
 * only in the database's CHECK constraint (PaymentApiImpl.toView's {@code valueOf}). */
public enum PaymentStatus { PENDING, IN_DOUBT, CONFIRMED, FAILED }
