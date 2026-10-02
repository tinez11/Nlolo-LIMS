package tz.co.nlolo.lifeplatform.accumulation.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * A fixed-term deposit's interest: the rate is FOR THE TERM (spec D2), earned evenly by day (D4).
 * Not step 3's InterestCalculator -- that compounds an annual rate daily, and a quarter of 89-92
 * days would never pay exactly 3%.
 */
public final class DepositInterest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private DepositInterest() {}

    /** principal x rate / 100 x days held / days in the term; held is clamped to [0, term]. Rounded once. */
    public static BigDecimal accrued(BigDecimal principal, BigDecimal ratePercent, LocalDate start, LocalDate maturity,
                                     LocalDate asOf) {
        long term = ChronoUnit.DAYS.between(start, maturity);
        if (term <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        long held = Math.max(0, Math.min(term, ChronoUnit.DAYS.between(start, asOf)));
        return principal.multiply(ratePercent).multiply(BigDecimal.valueOf(held))
            .divide(HUNDRED.multiply(BigDecimal.valueOf(term)), 2, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal full(BigDecimal principal, BigDecimal ratePercent) {
        return principal.multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
    }
}
