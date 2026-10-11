package tz.co.nlolo.lifeplatform.reporting.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** How a figure is written into a report row. Strings, because money and counts never travel as JSON
 * numbers on this platform. A rate with a zero denominator is blank, not zero and not infinity. */
public final class Figures {
    private Figures() {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String count(long value) {
        return Long.toString(value);
    }

    public static String percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return "";
        return numerator.multiply(HUNDRED).divide(denominator, 2, RoundingMode.HALF_UP).toPlainString();
    }
}
