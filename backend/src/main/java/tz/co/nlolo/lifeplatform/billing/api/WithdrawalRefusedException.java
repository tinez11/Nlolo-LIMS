package tz.co.nlolo.lifeplatform.billing.api;

/**
 * A withdrawal from a policy's balance the platform will not ask for or decide as asked (money movement step 3a): more
 * than is on the balance, one already in flight, the wrong state, or a second person needed. 409, the reason in words.
 */
public class WithdrawalRefusedException extends RuntimeException {
    public WithdrawalRefusedException(String message) {
        super(message);
    }
}
