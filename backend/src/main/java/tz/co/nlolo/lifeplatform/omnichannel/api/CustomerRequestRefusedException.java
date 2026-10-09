package tz.co.nlolo.lifeplatform.omnichannel.api;

/**
 * Something a customer asked the portal for that cannot be done as asked, in words they can act on: 422, or 409 where it
 * was already done (2026-10-08, the customer portal design step 5).
 */
public class CustomerRequestRefusedException extends RuntimeException {

    private final boolean conflict;

    public CustomerRequestRefusedException(String message) {
        this(message, false);
    }

    public CustomerRequestRefusedException(String message, boolean conflict) {
        super(message);
        this.conflict = conflict;
    }

    public boolean conflict() {
        return conflict;
    }
}
