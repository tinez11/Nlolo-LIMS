package tz.co.nlolo.lifeplatform.billing.api;

/**
 * A payment was requested against an invoice that owes nothing: paid, credited, or both, to its
 * full charged amount. 409 -- the request is well formed, it conflicts with the invoice's state.
 */
public class NothingOwedException extends RuntimeException {
    public NothingOwedException(String message) {
        super(message);
    }
}
