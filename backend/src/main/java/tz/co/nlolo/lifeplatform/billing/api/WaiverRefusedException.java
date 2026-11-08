package tz.co.nlolo.lifeplatform.billing.api;

/**
 * A waiver, or the undoing of one, that the invoice's state does not allow (2026-11-08): a second waiver of an
 * invoice already waived, a waiver of one already paid, a waiver of the premium that starts a proposed policy's
 * cover, or undoing a waiver on an invoice that is not waived. 409 -- well formed, in conflict with the invoice.
 */
public class WaiverRefusedException extends RuntimeException {
    public WaiverRefusedException(String message) {
        super(message);
    }
}
