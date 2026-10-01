package tz.co.nlolo.lifeplatform.accumulation.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * Interest on a daily balance, compounding daily -- including on interest accrued but not yet
 * posted (spec §10.7). Pure: no repository, no clock, no rounding. The caller rounds ONCE.
 *
 * <p>The declared rate is an EFFECTIVE annual rate, so the daily factor is {@code (1+r)^(1/n) - 1}
 * with {@code n} the length of the day's own year. Computed as {@code expm1(log1p(r)/n)}, which
 * keeps full double precision for a factor near zero -- {@code Math.pow(1+r, 1/n) - 1} would lose
 * its leading digits to the subtraction.
 */
public final class InterestCalculator {
    private InterestCalculator() {}

    private static final MathContext MC = MathContext.DECIMAL128;

    public record Movement(LocalDate effectiveDate, BigDecimal amount) {}

    public static BigDecimal dailyFactor(BigDecimal annualRatePercent, int daysInYear) {
        double r = annualRatePercent.doubleValue() / 100.0;
        return new BigDecimal(Math.expm1(Math.log1p(r) / daysInYear), MC);
    }

    /**
     * @param opening    the balance at the start of {@code from} -- every entry effective before it
     * @param movements  entries effective within {@code [from, to]}; any order
     * @return interest earned over {@code [from, to]} inclusive, unrounded
     */
    public static BigDecimal interest(BigDecimal opening, List<Movement> movements, LocalDate from, LocalDate to,
                                      Function<LocalDate, BigDecimal> annualRatePercent) {
        BigDecimal balance = opening;
        BigDecimal accrued = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            for (Movement m : movements) {
                if (m.effectiveDate().equals(d)) {
                    balance = balance.add(m.amount());
                }
            }
            BigDecimal factor = dailyFactor(annualRatePercent.apply(d), d.lengthOfYear());
            accrued = accrued.add(balance.add(accrued).multiply(factor, MC), MC);
        }
        return accrued;
    }
}
