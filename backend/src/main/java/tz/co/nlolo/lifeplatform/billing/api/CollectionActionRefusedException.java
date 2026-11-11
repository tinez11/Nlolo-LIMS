package tz.co.nlolo.lifeplatform.billing.api;

/**
 * A call or reminder on a premium the platform will not record as asked (2026-11-11, premiums to follow up): no
 * outcome, a promise in the past, a premium no longer owed, a second reminder the same day. 409, the reason in words.
 */
public class CollectionActionRefusedException extends RuntimeException {
    public CollectionActionRefusedException(String message) {
        super(message);
    }
}
