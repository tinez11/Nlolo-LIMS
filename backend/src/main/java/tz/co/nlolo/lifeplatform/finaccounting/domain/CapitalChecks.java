package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The three questions about the company's capital (2026-11-14, shareholders register): does the register agree with
 * the books, do Tanzanians hold at least one-third of the shares, and is paid-up capital at least the minimum finance
 * entered. Pure. {@code figure}: the difference, the Tanzanian share held, or how much is short; null where there is
 * nothing to say.
 */
public final class CapitalChecks {

    public static final String MET = "MET";
    public static final String NOT_MET = "NOT_MET";
    public static final String NOT_SET = "NOT_SET";

    private CapitalChecks() {}

    public record Check(String state, String figure) {}

    public record Result(Check books, Check tanzanian, Check minimum) {}

    public static Result of(BigDecimal registerCapital, BigDecimal ledgerCapital, long tanzanianShares, long allShares,
                            BigDecimal minimum) {
        BigDecimal difference = registerCapital.subtract(ledgerCapital).abs();
        Check books = difference.signum() == 0 ? new Check(MET, null)
            : new Check(NOT_MET, ShareIssueRules.money(difference));

        Check tanzanian;
        if (allShares == 0) {
            tanzanian = new Check(NOT_MET, null);
        } else {
            BigDecimal percent = BigDecimal.valueOf(tanzanianShares).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(allShares), 1, RoundingMode.HALF_UP);
            boolean met = tanzanianShares * 3 >= allShares;   // exactly: at least one-third
            tanzanian = new Check(met ? MET : NOT_MET, percent.toPlainString() + "%");
        }

        Check min;
        if (minimum == null) {
            min = new Check(NOT_SET, null);
        } else {
            BigDecimal shortBy = minimum.subtract(ledgerCapital);
            min = shortBy.signum() <= 0 ? new Check(MET, null) : new Check(NOT_MET, ShareIssueRules.money(shortBy));
        }
        return new Result(books, tanzanian, min);
    }
}
