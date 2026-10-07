package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * P-19's split (IFRS 17 I5b, spec section 4): each of the month's three totals shared over the groups of insurance
 * contracts by a driver -- maintenance by policies in force, claims handling by claims notified in the month, acquisition
 * by policies issued in it -- to the cent, the leftover cents to the largest remainders (ties by group order). A driver
 * at zero in every group falls back to in force, then to equal shares. IFRS 9 and reinsurance groups get nothing. Pure.
 */
public final class ExpenseAllocationSplit {

    public static final String MAINTENANCE = "MAINTENANCE";
    public static final String CLAIMS_HANDLING = "CLAIMS_HANDLING";
    public static final String ACQUISITION = "ACQUISITION";
    public static final Set<String> MODELS = Set.of("GMM", "VFA", "PAA");

    /** One group and its three driver counts. */
    public record Group(String key, String model, long inForce, long claims, long issued) {}

    /** One posted line: a group's share of one category, the account it goes to and the driver it was shared by. */
    public record Line(String group, String model, String category, String account, String driver, long driverCount,
                       BigDecimal amount) {}

    private ExpenseAllocationSplit() {}

    /**
     * The lines, by category and then group key, zero amounts left out. {@code paaExpensedModels} holds "PAA" when the
     * register says PAA acquisition cash flows are expensed when incurred (para 59(a)).
     */
    public static List<Line> split(BigDecimal maintenance, BigDecimal claimsHandling, BigDecimal acquisition,
                                   List<Group> groups, Set<String> paaExpensedModels) {
        List<Group> insurance = groups.stream().filter(g -> MODELS.contains(g.model()))
            .sorted(Comparator.comparing(Group::key)).toList();
        List<Line> lines = new ArrayList<>();
        category(lines, insurance, MAINTENANCE, maintenance, "IN_FORCE", Group::inForce, g -> "5210");
        category(lines, insurance, CLAIMS_HANDLING, claimsHandling, "CLAIMS", Group::claims, g -> "5215");
        category(lines, insurance, ACQUISITION, acquisition, "ISSUED", Group::issued,
            g -> acquisitionAccount(g.model(), paaExpensedModels.contains(g.model())));
        return lines;
    }

    /** GMM and VFA spread acquisition cash flows through 2123; PAA does too, unless expensed when incurred (5310). */
    public static String acquisitionAccount(String model, boolean expensedWhenIncurred) {
        return "PAA".equals(model) && expensedWhenIncurred ? "5310" : "2123";
    }

    /** {@code total} shared in proportion to {@code weights}, to the cent; the cents left go to the largest remainders. */
    public static List<BigDecimal> share(BigDecimal total, List<Long> weights) {
        BigInteger cents = total.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toBigIntegerExact();
        BigInteger sum = weights.stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
        List<BigInteger> base = new ArrayList<>();
        List<BigInteger> remainder = new ArrayList<>();
        BigInteger given = BigInteger.ZERO;
        for (Long w : weights) {
            BigInteger[] qr = cents.multiply(BigInteger.valueOf(w)).divideAndRemainder(sum);
            base.add(qr[0]);
            remainder.add(qr[1]);
            given = given.add(qr[0]);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing((Integer i) -> remainder.get(i)).reversed().thenComparing(i -> i));
        int left = cents.subtract(given).intValueExact();
        for (int k = 0; k < left; k++) {
            int i = order.get(k);
            base.set(i, base.get(i).add(BigInteger.ONE));
        }
        return base.stream().map(c -> new BigDecimal(c, 2)).toList();
    }

    private static void category(List<Line> lines, List<Group> groups, String category, BigDecimal total, String driver,
                                 ToLongFunction<Group> count, Function<Group, String> account) {
        if (total == null || total.signum() == 0 || groups.isEmpty()) {
            return;
        }
        String used = driver;
        ToLongFunction<Group> weight = count;
        if (groups.stream().mapToLong(count).sum() == 0) {
            used = "IN_FORCE";
            weight = Group::inForce;
            if (groups.stream().mapToLong(Group::inForce).sum() == 0) {
                used = "EQUAL";
                weight = g -> 1L;
            }
        }
        ToLongFunction<Group> w = weight;
        List<BigDecimal> shares = share(total, groups.stream().map(w::applyAsLong).toList());
        for (int i = 0; i < groups.size(); i++) {
            if (shares.get(i).signum() > 0) {
                Group g = groups.get(i);
                lines.add(new Line(g.key(), g.model(), category, account.apply(g), used, w.applyAsLong(g), shares.get(i)));
            }
        }
    }
}
