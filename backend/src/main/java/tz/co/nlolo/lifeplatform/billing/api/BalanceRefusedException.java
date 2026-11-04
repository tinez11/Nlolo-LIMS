package tz.co.nlolo.lifeplatform.billing.api;

/**
 * Something asked of a policy's balance that the platform will not do as asked (money movement steps 3a, 3b): a
 * withdrawal of more than is on it or with one already in flight, a reversal of a deposit already reversed, the wrong
 * state, or a second person needed. 409, the reason in words.
 */
public class BalanceRefusedException extends RuntimeException {
    public BalanceRefusedException(String message) {
        super(message);
    }
}
