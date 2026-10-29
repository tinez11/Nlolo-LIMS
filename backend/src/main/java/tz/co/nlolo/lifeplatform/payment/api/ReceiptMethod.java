package tz.co.nlolo.lifeplatform.payment.api;

/**
 * How money came in that a person receipts by hand (2026-10-27, user decision: every method manual until a payment
 * aggregator is chosen). Mobile money the gateway pulls is not here: it has no receipt to record.
 */
public enum ReceiptMethod {
    /** Notes and coins, at a branch or to an agent. */
    CASH,
    /** Paid into the insurer's bank account at a branch or by transfer; the bank slip or transfer reference. */
    BANK_DEPOSIT,
    /** A cheque, by its number, once it has cleared. */
    CHEQUE,
    /** Paid to the insurer's Lipa number (a mobile-money merchant till); the transaction id from the confirmation. */
    LIPA_NUMBER,
    /** Paid by scanning the insurer's TANQR code; the reference the customer's app shows. */
    TANQR
}
