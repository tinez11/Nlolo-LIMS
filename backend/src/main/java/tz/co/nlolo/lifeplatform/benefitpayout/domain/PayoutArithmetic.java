package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Every money figure the payout engine computes, in one place, HALF_EVEN to cents. */
public final class PayoutArithmetic {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 2;

    private PayoutArithmetic() {}

    public static BigDecimal percentOf(BigDecimal base, BigDecimal percent) {
        return base.multiply(percent).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Split a policy year's amount into equal instalments, the LAST one absorbing the rounding
     * remainder so the parts add back to the year exactly.
     *
     * <p>Rounding each part independently would lose up to a cent twelve times a year, every year,
     * on a benefit nobody reconciles -- the customer is quietly short-paid and the schedule still
     * looks right. Putting the difference on one instalment keeps the total true and makes the
     * adjustment visible in one place.
     */
    public static List<BigDecimal> splitYear(BigDecimal yearAmount, int parts) {
        BigDecimal exactYear = yearAmount.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal each = exactYear.divide(BigDecimal.valueOf(parts), MONEY_SCALE, RoundingMode.DOWN);
        List<BigDecimal> out = new ArrayList<>(parts);
        for (int i = 0; i < parts - 1; i++) {
            out.add(each);
        }
        out.add(exactYear.subtract(each.multiply(BigDecimal.valueOf(parts - 1L))));
        return out;
    }

    /** Paid-up restatement on step 1's PROPORTIONATE basis: amount x paid-up SA / original SA. */
    public static BigDecimal restate(BigDecimal amount, BigDecimal paidUpSumAssured, BigDecimal originalSumAssured) {
        return amount.multiply(paidUpSumAssured).divide(originalSumAssured, MONEY_SCALE, RoundingMode.HALF_EVEN);
    }
}
