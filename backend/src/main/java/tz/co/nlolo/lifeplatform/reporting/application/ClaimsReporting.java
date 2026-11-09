package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.CompareWith;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.ClaimFact;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;

/**
 * What every claims variance report shares (2026-11-09, the reports redesign batch 2; the user chose variance against
 * the prior period). The definitions are stated once here and on each report:
 * <ul>
 *   <li><b>Claims incurred</b> are claims APPROVED in a period, at their approved amount -- a claim has no amount
 *       until it is approved.</li>
 *   <li><b>Open</b> means registered, and neither paid nor declined.</li>
 *   <li><b>Large</b> means an approved amount of {@link #LARGE_CLAIM} or more.</li>
 *   <li>The payment target is {@link #TARGET_DAYS} days from registration.</li>
 * </ul>
 */
final class ClaimsReporting {

    private ClaimsReporting() {}

    /** A claim this size or more is shown apart, so one large death claim does not hide the trend. */
    static final BigDecimal LARGE_CLAIM = new BigDecimal("20000000.00");
    /** Days from registration to payment the business aims for. */
    static final int TARGET_DAYS = 30;

    /** A span of days: this period, or the one it is compared with. */
    record Span(LocalDate from, LocalDate to, String label) {
        boolean holds(LocalDate d) {
            return d != null && !d.isBefore(from) && !d.isAfter(to);
        }
    }

    static Span current(ReportFilter f) {
        return new Span(f.monthFrom().atDay(1), f.monthTo().atEndOfMonth(), StatementReports.span(f.monthFrom(), f.monthTo()));
    }

    /** The compared span, or null when the run compares with nothing. */
    static Span prior(ReportFilter f) {
        YearMonth[] m = f.comparisonMonths();
        return m == null ? null : new Span(m[0].atDay(1), m[1].atEndOfMonth(), StatementReports.span(m[0], m[1]));
    }

    /** The definition a run needs: this span's end, or the prior's when the run compares (both read the same rows). */
    static LocalDate readThrough(ReportFilter f) {
        return f.monthTo().atEndOfMonth();
    }

    // --- the count of a set and its sum ---

    record Tally(long count, BigDecimal amount) {
        BigDecimal average() {
            return count == 0 ? BigDecimal.ZERO : amount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        }
    }

    static Tally tally(List<ClaimFact> claims, Predicate<ClaimFact> which, Function<ClaimFact, BigDecimal> amount) {
        long n = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (ClaimFact c : claims) {
            if (which.test(c)) {
                n++;
                BigDecimal a = amount.apply(c);
                if (a != null) sum = sum.add(a);
            }
        }
        return new Tally(n, sum);
    }

    static Predicate<ClaimFact> approvedIn(Span s) {
        return c -> s != null && s.holds(c.approvedOn());
    }

    static boolean large(ClaimFact c) {
        return c.approvedAmount() != null && c.approvedAmount().compareTo(LARGE_CLAIM) >= 0;
    }

    /**
     * Why claims incurred moved: more or fewer claims (count effect, at the prior average) and bigger or smaller ones
     * (severity effect, on this period's count). The two add to the change exactly: the severity effect is the change
     * less the count effect, so any rounding lands there.
     */
    record Variance(BigDecimal change, BigDecimal countEffect, BigDecimal severityEffect) {
        static Variance of(Tally now, Tally before) {
            BigDecimal change = now.amount().subtract(before.amount());
            BigDecimal countEffect = BigDecimal.valueOf(now.count() - before.count()).multiply(before.average())
                .setScale(2, RoundingMode.HALF_UP);
            return new Variance(change, countEffect, change.subtract(countEffect));
        }
    }

    // --- the shape every claims table and figure takes ---

    /** "Change %" of a prior; null when the prior is zero or absent. */
    static String percent(BigDecimal now, BigDecimal before) {
        if (before == null || before.signum() == 0) return null;
        return now.subtract(before).multiply(BigDecimal.valueOf(100)).divide(before.abs(), 1, RoundingMode.HALF_UP)
            .toPlainString();
    }

    static ReportFigure money(String key, String label, BigDecimal now, BigDecimal before, String goodWhen, boolean lead) {
        return new ReportFigure(key, label, Figures.money(now), before == null ? null : Figures.money(before),
            before == null ? null : Figures.money(now.subtract(before)), percent(now, before), goodWhen, lead, "MONEY");
    }

    static ReportFigure count(String key, String label, long now, Long before, String goodWhen, boolean lead) {
        return new ReportFigure(key, label, Long.toString(now), before == null ? null : Long.toString(before),
            before == null ? null : Long.toString(now - before),
            before == null ? null : percent(BigDecimal.valueOf(now), BigDecimal.valueOf(before)), goodWhen, lead, "COUNT");
    }

    static ReportFigure ratio(String key, String label, BigDecimal now, BigDecimal before, String goodWhen, String unit,
                              boolean lead) {
        return new ReportFigure(key, label, now == null ? "" : now.toPlainString(),
            before == null ? null : before.toPlainString(),
            now == null || before == null ? null : now.subtract(before).toPlainString(), null, goodWhen, lead, unit);
    }

    /** Line, then count and amount for this period, and for the prior with its change when comparing. */
    static List<ReportColumn> columns(String first, Span now, Span before) {
        List<ReportColumn> columns = new ArrayList<>();
        columns.add(new ReportColumn("line", first, TEXT));
        columns.add(new ReportColumn("count", "Claims " + now.label(), COUNT));
        columns.add(new ReportColumn("amount", "Amount " + now.label(), MONEY));
        if (before != null) {
            columns.add(new ReportColumn("priorCount", "Claims " + before.label(), COUNT));
            columns.add(new ReportColumn("priorAmount", "Amount " + before.label(), MONEY));
            columns.add(new ReportColumn("change", "Change", MONEY));
            columns.add(new ReportColumn("changePercent", "Change %", PERCENT));
        }
        return columns;
    }

    static Map<String, String> row(String label, Tally now, Tally before, String goodWhen) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("line", label);
        row.put("count", Figures.count(now.count()));
        row.put("amount", Figures.money(now.amount()));
        if (before != null) {
            row.put("priorCount", Figures.count(before.count()));
            row.put("priorAmount", Figures.money(before.amount()));
            row.put("change", Figures.money(now.amount().subtract(before.amount())));
            String pct = percent(now.amount(), before.amount());
            row.put("changePercent", pct == null ? "" : pct);
        }
        if (goodWhen != null) row.put("_goodWhen", goodWhen);
        return row;
    }

    /** A claim type as people say it: DEATH -> Death, CRITICAL_ILLNESS -> Critical illness. */
    static String typeName(String type) {
        if (type == null) return "Unknown";
        String words = type.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    static boolean compares(ReportFilter f) {
        return f.compareWith() != null && f.compareWith() != CompareWith.NONE;
    }
}
