package tz.co.nlolo.lifeplatform.payment.api;

/**
 * How money leaves the business.
 *
 * <p>There has only ever been one rail. {@code PaymentGatewayPort} exposes
 * {@code submitDisbursement} and nothing else, so every payout — a policy loan, a commission,
 * a claim settlement — went to the mobile-money gateway, which in this environment is a mock
 * with no authentication.
 */
public enum DisbursementMethod {

    /** The gateway rail: submitted to the provider, completed by their callback. */
    MOBILE_MONEY,

    /**
     * Recorded here, posted to the ledger, and moved by a person in the bank's own portal.
     *
     * <p>Not a second gateway, and deliberately not pretending to be one. A multi-million
     * shilling payout to a lender does not belong on a mobile-money rail (spec §2.9), and the
     * platform should not claim to have moved money it did not move. What it records is that
     * the instruction exists, what it is for, and — once finance confirms — that the transfer
     * happened and under which bank reference.
     */
    EFT
}
