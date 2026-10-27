package tz.co.nlolo.lifeplatform.payment.api;

/** A receipt the platform will not record or decide as asked (2026-10-27): the reason is said in words, as a 409. */
public class ReceiptRefusedException extends RuntimeException {

    public ReceiptRefusedException(String message) {
        super(message);
    }
}
