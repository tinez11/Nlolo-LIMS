package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The running term of a fixed-term deposit, worked forward (2026-10-09, the user's request for a schedule "like
 * others"): what it pays at maturity, and what it would pay if closed early at each monthly date -- interest to that
 * day, earned evenly by day at the term's rate (DepositInterest), before any early-closing charge the product sets.
 *
 * @param ifClosedEarly one row per month of the term, the last on the maturity date
 */
public record DepositScheduleView(BigDecimal principal, String currency, int termMonths, BigDecimal ratePercent,
                                  LocalDate startDate, LocalDate maturityDate, BigDecimal interestAtMaturity,
                                  BigDecimal amountAtMaturity, List<Row> ifClosedEarly) {

    public record Row(LocalDate closedOn, BigDecimal interest, BigDecimal paidOut) {}
}
