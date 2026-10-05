package tz.co.nlolo.lifeplatform.unitlinked.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Every rounding rule the unit ledger applies (spec §5), in one place: money 2 dp HALF_EVEN and rounded once;
 * units 6 dp, TRUNCATED on a buy so the customer never gets more than the money buys, CEILED on a money sale so
 * the charge is covered, never more than the holding. A price is always greater than zero -- never defaulted.
 */
public final class UnitArithmetic {

    public record Allocated(BigDecimal allocated, BigDecimal charge) {}

    public record Weighted(String key, BigDecimal weight) {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private UnitArithmetic() {}

    /** {@code premium × percent / 100}, rounded once; the allocation charge is exactly the rest. */
    public static Allocated allocate(BigDecimal premium, BigDecimal percent) {
        BigDecimal allocated = premium.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_EVEN);
        return new Allocated(allocated, premium.subtract(allocated));
    }

    /**
     * {@code amount} in proportion to the weights, each part 2 dp HALF_EVEN, in the order given. The remainder goes
     * to the largest weight (the first by key on a tie), so the parts always sum to exactly {@code amount}.
     */
    public static List<BigDecimal> split(BigDecimal amount, List<Weighted> weights) {
        BigDecimal total = weights.stream().map(Weighted::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weights.isEmpty() || total.signum() <= 0) {
            throw new IllegalArgumentException("A split needs positive weights");
        }
        List<BigDecimal> parts = new ArrayList<>();
        for (Weighted w : weights) {
            parts.add(amount.multiply(w.weight()).divide(total, 2, RoundingMode.HALF_EVEN));
        }
        BigDecimal remainder = amount.subtract(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        int largest = 0;
        for (int i = 1; i < weights.size(); i++) {
            int c = weights.get(i).weight().compareTo(weights.get(largest).weight());
            if (c > 0 || (c == 0 && weights.get(i).key().compareTo(weights.get(largest).key()) < 0)) {
                largest = i;
            }
        }
        parts.set(largest, parts.get(largest).add(remainder));
        return parts;
    }

    public static BigDecimal unitsBought(BigDecimal money, BigDecimal price) {
        requirePrice(price);
        return money.divide(price, 6, RoundingMode.DOWN);
    }

    /** The units a money sale cancels: enough to cover it, never more than the holding. */
    public static BigDecimal unitsToSell(BigDecimal money, BigDecimal price, BigDecimal held) {
        requirePrice(price);
        return money.divide(price, 6, RoundingMode.CEILING).min(held);
    }

    public static BigDecimal proceeds(BigDecimal units, BigDecimal price) {
        requirePrice(price);
        return units.multiply(price).setScale(2, RoundingMode.HALF_EVEN);
    }

    private static void requirePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("A unit price must be greater than zero, never defaulted");
        }
    }
}
