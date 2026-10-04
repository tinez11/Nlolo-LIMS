package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import tz.co.nlolo.lifeplatform.product.api.AnnuityFrequencies;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;

/**
 * An annuity stream's calendar and amounts (product step 5). Pure, so the arithmetic is tested
 * without a database.
 */
public final class AnnuitySchedule {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private AnnuitySchedule() {}

    /**
     * The n-th instalment's due date, 0-based, stepped by calendar months from the first. Computed
     * from the first date every time, so a 31st stays the 31st where the month has one (Jan 31 ->
     * Feb 28 -> Mar 31), never drifting to the 28th for good.
     */
    public static LocalDate dueDate(LocalDate firstDue, String frequency, int n) {
        return firstDue.plusMonths((long) n * AnnuityFrequencies.monthsPer(frequency));
    }

    /**
     * What falls due on {@code due}: the locked base, escalated once for each whole year since the
     * first payment, times the survivor multiplier -- computed from the base at full precision and
     * rounded HALF_EVEN to cents once, so rounded figures are never compounded.
     */
    public static BigDecimal amount(BigDecimal base, BigDecimal escalationPercent, LocalDate firstDue, LocalDate due,
                                    BigDecimal multiplier) {
        int years = Math.max(0, Period.between(firstDue, due).getYears());
        BigDecimal growth = BigDecimal.ONE.add(escalationPercent.divide(HUNDRED)).pow(years);
        return base.multiply(growth).multiply(multiplier).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * The total of every instalment due after {@code fromExclusive} up to {@code toInclusive}, from the
     * stream's own figures rather than from expanded rows -- a ten-year guarantee reaches far past the
     * twelve months the stream has expanded so far.
     */
    public static BigDecimal sumBetween(LocalDate firstDue, String frequency, BigDecimal base, BigDecimal escalationPercent,
                                        BigDecimal multiplier, LocalDate fromExclusive, LocalDate toInclusive) {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        if (toInclusive == null || !toInclusive.isAfter(fromExclusive)) {
            return total;
        }
        for (int n = 0; ; n++) {
            LocalDate due = dueDate(firstDue, frequency, n);
            if (due.isAfter(toInclusive)) {
                return total;
            }
            if (due.isAfter(fromExclusive)) {
                total = total.add(amount(base, escalationPercent, firstDue, due, multiplier));
            }
        }
    }
}
