package tz.co.nlolo.lifeplatform.benefitpayout.api;

/**
 * A refused payout transition: understood and rejected on a rule, not malformed. Mapped to 422
 * with this message, which the console shows verbatim.
 */
public class PayoutStateException extends RuntimeException {
    public PayoutStateException(String message) { super(message); }
}
