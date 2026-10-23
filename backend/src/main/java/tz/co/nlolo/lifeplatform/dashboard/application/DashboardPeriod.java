package tz.co.nlolo.lifeplatform.dashboard.application;

import tz.co.nlolo.lifeplatform.dashboard.api.Period;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The months a dashboard's ranged figures cover (2026-10-23), and the little arithmetic every dashboard shares.
 *
 * <p>The console picks a range -- this month, the quarter, the year to date, the last twelve months or any months it
 * likes -- and sends it as two months. The server owns everything after that: which months a series has, what they
 * are compared with, every total, share and average. The browser draws; it never adds.
 */
public record DashboardPeriod(YearMonth from, YearMonth to, YearMonth current) {

    /** The widest range a dashboard reads: three years of monthly points is already more than a chart can show well. */
    static final int MAX_MONTHS = 36;

    /**
     * @param from null for January of the current year
     * @param to null for the current month
     * @throws IllegalArgumentException for a range that ends after this month, runs backwards, or is too wide
     */
    public static DashboardPeriod of(String from, String to, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        YearMonth start = from == null || from.isBlank() ? YearMonth.of(current.getYear(), 1) : parse(from, "from");
        YearMonth end = to == null || to.isBlank() ? current : parse(to, "to");
        if (end.isAfter(current)) {
            throw new IllegalArgumentException("A dashboard range cannot end after this month (" + current + ")");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("A dashboard range must start on or before its end: " + start + " to " + end);
        }
        if (months(start, end).size() > MAX_MONTHS) {
            throw new IllegalArgumentException("A dashboard range covers at most " + MAX_MONTHS + " months");
        }
        return new DashboardPeriod(start, end, current);
    }

    private static YearMonth parse(String value, String name) {
        try {
            return YearMonth.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + name + "' must be a month as YYYY-MM, not '" + value + "'");
        }
    }

    public YearMonth compareFrom() {
        return from.minusYears(1);
    }

    public YearMonth compareTo() {
        return to.minusYears(1);
    }

    public List<YearMonth> months() {
        return months(from, to);
    }

    public List<YearMonth> compareMonths() {
        return months(compareFrom(), compareTo());
    }

    public Period toView() {
        return new Period(from.toString(), to.toString(), compareFrom().toString(), compareTo().toString(),
            current.toString());
    }

    static List<YearMonth> months(YearMonth from, YearMonth to) {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            months.add(m);
        }
        return months;
    }

    /** Every month of {@code months}, in order, its value from {@code values} or {@code zero} when it has none. */
    static <T> List<T> fill(List<YearMonth> months, Map<YearMonth, T> values, Function<YearMonth, T> zero) {
        return months.stream().map(m -> values.containsKey(m) ? values.get(m) : zero.apply(m)).toList();
    }

    /** {@code now} against {@code before}, one decimal place, signed; null when there is nothing to compare with. */
    static String changePercent(BigDecimal now, BigDecimal before) {
        if (now == null || before == null || before.signum() == 0) {
            return null;
        }
        return now.subtract(before).multiply(BigDecimal.valueOf(100))
            .divide(before, 1, RoundingMode.HALF_UP).toPlainString();
    }

    /** {@code part} of {@code whole}, one decimal place; null when the whole is nothing. */
    static String percentOf(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP).toPlainString();
    }

    /** One piece of a whole before it becomes a {@link Share}: a key and its figure. */
    record Slice(String key, BigDecimal value) {}

    /** The most slices a chart shows: five colours, so the fifth onwards fold into OTHER. */
    static final int MAX_SLICES = 5;

    /**
     * Slices as shares of their own whole, largest first, at most {@link #MAX_SLICES}: past that the smallest are
     * added into one OTHER slice HERE, so the console never adds and the ring still adds up to the whole.
     *
     * @param amount the slice's figure as money, or null when the slices are counts
     * @param count the slice's figure as a count, or null when they are money
     */
    static List<tz.co.nlolo.lifeplatform.dashboard.api.Share> shares(List<Slice> slices,
            Function<BigDecimal, tz.co.nlolo.lifeplatform.dashboard.api.Money> amount, Function<BigDecimal, Long> count) {
        List<Slice> sorted = slices.stream().filter(s -> s.value() != null && s.value().signum() > 0)
            .sorted(java.util.Comparator.comparing(Slice::value).reversed()).toList();
        BigDecimal whole = sorted.stream().map(Slice::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Slice> kept = new ArrayList<>(sorted);
        if (sorted.size() > MAX_SLICES) {
            kept = new ArrayList<>(sorted.subList(0, MAX_SLICES - 1));
            kept.add(new Slice("OTHER", sorted.subList(MAX_SLICES - 1, sorted.size()).stream().map(Slice::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        return kept.stream().map(s -> new tz.co.nlolo.lifeplatform.dashboard.api.Share(s.key(),
            amount == null ? null : amount.apply(s.value()), count == null ? null : count.apply(s.value()),
            percentOf(s.value(), whole))).toList();
    }

    /** Summed days over a count, one decimal place; null for no cases. */
    static String average(BigDecimal totalDays, long cases) {
        if (totalDays == null || cases == 0) {
            return null;
        }
        return totalDays.divide(BigDecimal.valueOf(cases), 1, RoundingMode.HALF_UP).toPlainString();
    }
}
