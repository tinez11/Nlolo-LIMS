package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A fixed-term deposit's rate grid. {@link #none()} for every other version.
 *
 * <p>A band is named by its start only and runs up to the next band's start, which it excludes;
 * the last band has no end. Bands so defined cannot leave a gap or overlap (the spec's D9), and a
 * deposit of 5,999,999.50 still finds its band.
 */
public record DepositPlan(List<DepositRateRow> rows) {

    public DepositPlan {
        rows = rows != null ? List.copyOf(rows) : List.of();
    }

    public static DepositPlan none() { return new DepositPlan(List.of()); }

    public boolean isDeposit() { return !rows.isEmpty(); }

    public List<Integer> terms() {
        return rows.stream().map(DepositRateRow::termMonths).distinct().sorted().toList();
    }

    /** Distinct by value, not scale: 500000 and 500000.00 are one band (a TreeSet compares, it does not equals). */
    public List<BigDecimal> bandStarts() {
        return List.copyOf(rows.stream().map(DepositRateRow::minAmount).collect(Collectors.toCollection(TreeSet::new)));
    }

    /** The band is the highest start at or below the amount; empty below the lowest band or for an unoffered term. */
    public Optional<BigDecimal> rateFor(BigDecimal amount, int termMonths) {
        Optional<BigDecimal> band = rows.stream().map(DepositRateRow::minAmount)
            .filter(start -> start.compareTo(amount) <= 0).max(Comparator.naturalOrder());
        return band.flatMap(start -> rows.stream()
            .filter(r -> r.minAmount().compareTo(start) == 0 && r.termMonths() == termMonths)
            .map(DepositRateRow::ratePercent).findFirst());
    }
}
