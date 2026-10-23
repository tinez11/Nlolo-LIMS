package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.AccountChargeView;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

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
 * and one missed is not made up. Charges chosen on the case (product V32) are not in it -- only the version's own,
 * its Account charges (V34) or else its rows.
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
                             BigDecimal interest, BigDecimal maturityValue, LocalDate maturityDate,
                             List<Point> schedule) {}

    /**
     * Where the account stands {@code month} months in (2026-10-23, the quotation's growth chart) -- every year end, or
     * every month end on a term of two years or less, and the maturity day last. Taken from THIS projection's own
     * running figures, before that day's contribution lands, so the chart and the totals are one calculation.
     *
     * @param value the balance with the interest earned but not yet posted, to the cent
     * @param interest every cent of interest earned so far, posted or not
     */
    public record Point(int month, LocalDate date, BigDecimal paidIn, BigDecimal charges, BigDecimal interest,
                        BigDecimal value) {}

    /** A month-by-month schedule up to this many months; year by year beyond it. */
    static final int MONTHLY_SCHEDULE_UP_TO = 24;

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
        return project(contribution, frequency, termMonths, ratePercent, start, plan, List.of());
    }

    /**
     * @param catalogue the version's Account charges (V34). When there are any they are the charges, as they are on the
     *     account: premium and deposit charges on each contribution (and the opening one on the first), monthly charges
     *     on a whole month, yearly ones in an anniversary's month, and maturity charges off the payout. Else the
     *     version's own rows.
     */
    public static Projection project(BigDecimal contribution, String frequency, int termMonths, BigDecimal ratePercent,
                                     LocalDate start, AccumulationPlan plan, List<AccountChargeView> catalogue) {
        LocalDate maturity = start.plusMonths(termMonths);
        int step = step(frequency);
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal accrued = BigDecimal.ZERO;
        BigDecimal paidIn = BigDecimal.ZERO;
        BigDecimal charges = BigDecimal.ZERO;
        BigDecimal interest = BigDecimal.ZERO;
        int paid = 0;
        LocalDate nextDue = start;
        int every = termMonths <= MONTHLY_SCHEDULE_UP_TO ? 1 : 12;
        List<Point> schedule = new java.util.ArrayList<>();
        int nextPoint = every;
        for (LocalDate d = start; !d.isAfter(maturity); d = d.plusDays(1)) {
            if (nextPoint < termMonths && d.equals(start.plusMonths(nextPoint))) {
                BigDecimal unposted = accrued.setScale(2, RoundingMode.HALF_EVEN);
                schedule.add(new Point(nextPoint, d, paidIn.setScale(2), charges.setScale(2, RoundingMode.HALF_EVEN),
                    interest.add(unposted).setScale(2, RoundingMode.HALF_EVEN),
                    balance.add(unposted).setScale(2, RoundingMode.HALF_EVEN)));
                nextPoint += every;
            }
            if (nextDue != null && d.equals(nextDue) && d.isBefore(maturity)) {
                BigDecimal allocation = catalogue.isEmpty()
                    ? contribution.multiply(charge(plan, start, d).contributionAllocationPercent())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN)
                    : taken(catalogue, PolicyYears.of(start, d),
                        paid == 0 ? Set.of("DEPOSIT", "PREMIUM", "OPENING") : Set.of("DEPOSIT", "PREMIUM"), contribution);
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
                    BigDecimal due = catalogue.isEmpty() ? charge(plan, start, d).monthlyPolicyFee()
                        : taken(catalogue, PolicyYears.of(start, d),
                            AccumulationApiImpl.anniversaryBetween(start, d.withDayOfMonth(1), d)
                                ? Set.of("MONTHLY", "YEARLY") : Set.of("MONTHLY"), balance.max(BigDecimal.ZERO));
                    BigDecimal fee = due.min(balance.max(BigDecimal.ZERO));
                    balance = balance.subtract(fee);
                    charges = charges.add(fee);
                }
            }
        }
        if (!catalogue.isEmpty() && balance.signum() > 0) {
            // The last policy year's maturity charges, off the payout -- as the account takes them.
            BigDecimal exit = taken(catalogue, PolicyYears.of(start, maturity.minusDays(1)), Set.of("MATURITY"), balance)
                .min(balance);
            balance = balance.subtract(exit);
            charges = charges.add(exit);
        }
        schedule.add(new Point(termMonths, maturity, paidIn.setScale(2), charges.setScale(2, RoundingMode.HALF_EVEN),
            interest.setScale(2, RoundingMode.HALF_EVEN), balance.setScale(2, RoundingMode.HALF_EVEN)));
        return new Projection(contribution, paid, paidIn.setScale(2), charges.setScale(2, RoundingMode.HALF_EVEN),
            interest.setScale(2, RoundingMode.HALF_EVEN), balance.setScale(2, RoundingMode.HALF_EVEN), maturity,
            List.copyOf(schedule));
    }

    /**
     * The smallest contribution, to the cent, whose projection reaches {@code target}. Found by halving: the value at
     * maturity only rises as the contribution does, so about thirty projections settle the cent whatever the charges.
     *
     * <p>Not a straight line through two projections, which this was: a monthly fee takes nothing from an account
     * that has nothing, so the line bent near zero, the first guess fell short, and the cent-by-cent climb that
     * followed ran a five-year projection per cent -- the quotation never came back (2026-10-21, CliveFF, a 1,000 fee).
     */
    public static Projection solve(BigDecimal target, String frequency, int termMonths, BigDecimal ratePercent,
                                   LocalDate start, AccumulationPlan plan) {
        return solve(target, frequency, termMonths, ratePercent, start, plan, List.of());
    }

    /** As above, on the version's Account charges when it has any (see the catalogue {@code project}). */
    public static Projection solve(BigDecimal target, String frequency, int termMonths, BigDecimal ratePercent,
                                   LocalDate start, AccumulationPlan plan, List<AccountChargeView> catalogue) {
        BigDecimal cent = new BigDecimal("0.01");
        BigDecimal low = BigDecimal.ZERO;     // reaches less than the target
        BigDecimal high = target.max(cent);   // paying the whole target every time reaches it, unless charges eat it
        Projection reached = project(high, frequency, termMonths, ratePercent, start, plan, catalogue);
        for (int doublings = 0; reached.maturityValue().compareTo(target) < 0; doublings++) {
            if (doublings == 40) {
                throw new IllegalArgumentException("No contribution reaches " + target.toPlainString()
                    + " on this product's charges over " + termMonths + " months");
            }
            low = high;
            high = high.multiply(BigDecimal.TWO);
            reached = project(high, frequency, termMonths, ratePercent, start, plan, catalogue);
        }
        while (high.subtract(low).compareTo(cent) > 0) {
            BigDecimal mid = low.add(high).divide(BigDecimal.TWO, 2, RoundingMode.DOWN);
            if (mid.compareTo(low) <= 0) mid = low.add(cent);
            Projection p = project(mid, frequency, termMonths, ratePercent, start, plan, catalogue);
            if (p.maturityValue().compareTo(target) >= 0) {
                high = mid;
                reached = p;
            } else {
                low = mid;
            }
        }
        return reached;
    }

    /** What the {@code when} charges of {@code policyYear} take on {@code base}, together. */
    private static BigDecimal taken(List<AccountChargeView> catalogue, int policyYear, Set<String> when, BigDecimal base) {
        return catalogue.stream().filter(c -> when.contains(c.when()) && c.covers(policyYear)).map(c -> c.on(base))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static AccumulationChargeRow charge(AccumulationPlan plan, LocalDate start, LocalDate on) {
        return plan.chargesFor(PolicyYears.of(start, on));
    }
}
