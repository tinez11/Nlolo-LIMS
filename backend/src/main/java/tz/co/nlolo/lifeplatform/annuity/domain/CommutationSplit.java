package tz.co.nlolo.lifeplatform.annuity.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A pension's balance at vesting, split into the lump sum and what buys the annuity (product step 5
 * D2, spec Q3). The lump sum is rounded once, HALF_EVEN to cents; the price is the exact rest, so the
 * two always add back to the balance.
 */
public final class CommutationSplit {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private CommutationSplit() {}

    public record Split(BigDecimal lumpSum, BigDecimal purchasePrice) {}

    public static Split of(BigDecimal balance, BigDecimal lumpSumPercent) {
        BigDecimal lumpSum = balance.multiply(lumpSumPercent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        return new Split(lumpSum, balance.subtract(lumpSum).setScale(2, RoundingMode.UNNECESSARY));
    }
}
