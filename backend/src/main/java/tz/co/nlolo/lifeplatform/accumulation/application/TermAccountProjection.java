package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * What a term account will be worth when it matures, before it exists (2026-10-21) -- and the contribution that gets a
 * customer to a figure they name ("5,000,000 in five years").
 *
 * <p><b>The account's own arithmetic, day by day.</b> Each contribution lands on its due date less the version's
 * allocation charge for that policy year; interest compounds daily at the locked rate exactly as
 * {@link InterestCalculator} credits it (its {@code dailyFactor}); each month-end posts the month's interest and takes
 * the version's monthly fee for a whole month on the books -- a part first month is free, as {@code postMonthEnd} has
 * it; and the maturity day takes the last days' interest. So a quote and the policy it becomes cannot disagree, save
 * for each month's rounding to the cent.
 *
 * <p><b>A projection, not a promise.</b> It assumes every contribution is paid on its due date; one paid late earns less,
 * and one missed is not made up. Charges chosen on the case (product V32) are not in it -- only the version's own.
 *
 * <p>Pure: no repository, no clock.
 */
public final class TermAccountProjection {

    private static final MathContext MC = MathContext.DECIMAL128;

    private TermAccountProjection() {}

    /**
     * @param contributions how many payments the term takes
     * @param paidIn what the customer pays, in total
     * @param charges allocation charges and monthly fees taken, in total
     * @param interest interest credited to maturity
     * @param maturityValue paid out on the maturity date
     */
    public record Projection(BigDecimal contribution, int contributions, BigDecimal paidIn, BigDecimal charges,
                             BigDecimal interest, BigDecimal maturityValue, LocalDate maturityDate) {}

    /** Months between payments; 0 for SINGLE, paid once at the start. */
    static int step(String frequency) {
        return switch (frequency) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            case "ANNUALLY" -> 12;
            case "SINGLE" -> 0;
            default -> throw new IllegalArgumentException("A term account is paid MONTHLY, QUARTERLY, ANNUALLY or SINGLE, not "
                + frequency);
        };
    }

    public static Projection project(BigDecimal contribution, String frequency, int termMonths, BigDecimal ratePercent,
                                     LocalDate start, AccumulationPlan plan) {
        LocalDate maturity = start.plusMonths(termMonths);
        int step = step(frequency);
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal accrued = BigDecimal.ZERO;
        BigDecimal paidIn = BigDecimal.ZERO;
        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal interest = BigDecimal.ZERO;
        int paid = 0;
        LocalDate nextDue = start;
        for (LocalDate d = start; !d.isAfter(maturity); d = d.plusDays(1)) {
            if (nextDue != null && d.equals(nextDue) && d.isBefore(maturity)) {
                BigDecimal allocation = contribution.multiply(charge(plan, start, d).contributionAllocationPercent())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
                balance = balance.add(contribution).subtract(allocation);
                paidIn = paidIn.add(contribution);
                charges = charges.add(allocation);
                paid++;
                nextDue = step == 0 ? null : start.plusMonths((long) paid * step);
            }
            BigDecimal factor = InterestCalculator.dailyFactor(ratePercent, d.lengthOfYear());
            accrued = accrued.add(balance.add(accrued).multiply(factor, MC), MC);
            boolean monthEnd = d.getDayOfMonth() == d.lengthOfMonth();
            if (monthEnd || d.equals(maturity)) {
                BigDecimal posted = accrued.setScale(2, RoundingMode.HALF_EVEN);
                balance = balance.add(posted);
                interest = interest.add(posted);
                accrued = BigDecimal.ZERO;
                // The month-end's fee, on a whole month of cover; the maturity day closes the account and takes none.
                if (monthEnd && !start.isAfter(d.withDayOfMonth(1))) {
                    BigDecimal fee = charge(plan, start, d).monthlyPolicyFee().min(balance.max(BigDecimal.ZERO));
                    balance = balance.subtract(fee);
                    charges = charges.add(fee);
                }
            }
        }
        return new Projection(contribution, paid, paidIn.setScale(2), charges.setScale(2, RoundingMode.HALF_EVEN),
            interest.setScale(2, RoundingMode.HALF_EVEN), balance.setScale(2, RoundingMode.HALF_EVEN), maturity);
    }

    /**
     * The contribution, to the cent above, whose projection reaches {@code target}. The value at maturity rises in step
     * with the contribution -- each one buys the same share of it -- so two projections fix the line, and a third
     * confirms the cent; the fees are the same whatever is paid.
     */
    public static Projection solve(BigDecimal target, String frequency, int termMonths, BigDecimal ratePercent,
                                   LocalDate start, AccumulationPlan plan) {
        BigDecimal base = project(BigDecimal.ZERO, frequency, termMonths, ratePercent, start, plan).maturityValue();
        BigDecimal perUnit = project(new BigDecimal("1000000"), frequency, termMonths, ratePercent, start, plan)
            .maturityValue().subtract(base).divide(new BigDecimal("1000000"), MC);
        BigDecimal contribution = target.subtract(base).divide(perUnit, 2, RoundingMode.CEILING).max(new BigDecimal("0.01"));
        Projection p = project(contribution, frequency, termMonths, ratePercent, start, plan);
        // Each month's rounding can leave it a cent short; the customer is never quoted a figure that falls below.
        while (p.maturityValue().compareTo(target) < 0) {
            contribution = contribution.add(new BigDecimal("0.01"));
            p = project(contribution, frequency, termMonths, ratePercent, start, plan);
        }
        return p;
    }

    private static AccumulationChargeRow charge(AccumulationPlan plan, LocalDate start, LocalDate on) {
        return plan.chargesFor(PolicyYears.of(start, on));
    }
}
