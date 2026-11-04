package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of a policy balance's statement, newest first (money movement step 5). Each line carries its signed amount
 * -- money in positive, money out negative -- and the balance after it, both summed by the server.
 */
public record BalanceStatementView(String policyNumber, String currency, long total, int page, int size,
                                   List<Line> lines) {

    /**
     * {@code seq} counts from 1 in the order the entries were written; {@code type} is DEPOSIT, PREMIUM_PAID, WITHDRAWAL,
     * REVERSAL or PREMIUM_REOPENED. {@code paymentId} names the deposit a DEPOSIT line made (its own page), and
     * {@code invoiceId} the premium a PREMIUM_PAID or PREMIUM_REOPENED line paid or reopened.
     */
    public record Line(UUID entryId, int seq, String type, Instant at, BigDecimal amount, BigDecimal balanceAfter,
                       UUID paymentId, UUID invoiceId, UUID withdrawalId, UUID reversesEntryId, String method,
                       String reference, String source) {}
}
