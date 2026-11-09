package tz.co.nlolo.lifeplatform.reporting.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A straight line through a run of monthly figures, by least squares, and how far the months sat from it (2026-11-09,
 * the solvency forecast). The spread is the standard deviation of the months around the line: the forecast's range is
 * the line plus or minus it, so a steady history gives a narrow range and a jumpy one a wide range.
 *
 * <p>Fewer than two months has no slope: the line is flat at what there is, and the spread is zero.
 */
record Trend(double intercept, double slope, double spread, int months) {

    static Trend fit(List<BigDecimal> values) {
        int n = values.size();
        if (n == 0) return new Trend(0, 0, 0, 0);
        double[] y = values.stream().mapToDouble(BigDecimal::doubleValue).toArray();
        if (n == 1) return new Trend(y[0], 0, 0, 1);
        double meanX = (n - 1) / 2.0;
        double meanY = 0;
        for (double v : y) meanY += v;
        meanY /= n;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < n; i++) {
            sxy += (i - meanX) * (y[i] - meanY);
            sxx += (i - meanX) * (i - meanX);
        }
        double slope = sxy / sxx;
        double intercept = meanY - slope * meanX;
        double squares = 0;
        for (int i = 0; i < n; i++) {
            double residual = y[i] - (intercept + slope * i);
            squares += residual * residual;
        }
        double spread = n > 2 ? Math.sqrt(squares / (n - 2)) : 0;
        return new Trend(intercept, slope, spread, n);
    }

    /** The line's value {@code ahead} months after the last month fitted (1 = the next month), to the cent. */
    BigDecimal ahead(int ahead) {
        return cents(intercept + slope * (months - 1 + ahead));
    }

    BigDecimal spreadCents() {
        return cents(spread);
    }

    private static BigDecimal cents(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
