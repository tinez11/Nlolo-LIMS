package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A savings account statement as a customer reads it (2026-10-07): every entry in date order with money in,
 * money out and the balance after it, framed by the opening and closing balance, and each kind of movement
 * totalled. Built from the accumulation module's own statement.
 */
public record SavingsStatementView(String policyNumber, String policyholderName, String productName, String currency,
                                   LocalDate periodFrom, LocalDate periodTo, BigDecimal openingBalance,
                                   BigDecimal closingBalance, List<Line> lines, List<Total> totals) {

    /** One entry: in or out (never both), and the balance after it. */
    public record Line(LocalDate date, String description, BigDecimal moneyIn, BigDecimal moneyOut, BigDecimal balance) {}

    /** One kind of movement over the period, e.g. Premiums or Withdrawals. */
    public record Total(String label, BigDecimal amount) {}
}
