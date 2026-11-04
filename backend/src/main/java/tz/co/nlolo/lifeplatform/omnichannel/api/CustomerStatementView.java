package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of a customer's own policy balance statement, newest first (money movement step 5): what each line was, its
 * signed amount (money out negative) and the balance after it -- the server's sums. Nothing a staff member alone sees.
 */
public record CustomerStatementView(String currency, long total, int page, int size, List<Line> lines) {

    /** {@code type}: DEPOSIT, PREMIUM_PAID, WITHDRAWAL, REVERSAL or PREMIUM_REOPENED. */
    public record Line(UUID entryId, String type, Instant at, BigDecimal amount, BigDecimal balanceAfter) {}
}
