package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView.ReceivablesAging;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView.MonthPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.CountPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.Share;
import tz.co.nlolo.lifeplatform.reporting.api.CategoryAmount;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyCount;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyAmount;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FinanceDashboard {

    /** PRODUCT.md: "Working currency is TZS." Another currency's collections are never added to this one's. */
    static final String WORKING_CURRENCY = "TZS";

    /**
     * The dunning level that publishes PolicyLapseRecommended -- the same 5 as billing's own
     * {@code ArrearsNotificationSweep.LAPSE_RECOMMENDATION_LEVEL} and the console's sidebar badge.
     */
    static final int LAPSE_RECOMMENDATION_LEVEL = 5;

    private static final PageRequest TOTAL_ONLY = PageRequest.of(0, 1);

    private final ReportingApi reporting;
    private final ManualJournalApi journals;
    private final BillingApi billing;
    private final PaymentApi payments;
    private final RecentActivity activity;

    public FinanceDashboard(ReportingApi reporting, ManualJournalApi journals, BillingApi billing, PaymentApi payments,
                            RecentActivity activity) {
        this.reporting = reporting;
        this.journals = journals;
        this.billing = billing;
        this.payments = payments;
        this.activity = activity;
    }

    /** @param today the civil date in Africa/Dar_es_Salaam, supplied by the caller */
    public FinanceDashboardView build(LocalDate today) {
        return build(DashboardPeriod.of(null, null, today), today);
    }

    /**
     * @param period the months the ranged figures cover (2026-10-23); the cards that were always "last complete
     *     month" and "as at today" keep their meaning whatever the range
     */
    public FinanceDashboardView build(DashboardPeriod period, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        YearMonth lastComplete = current.minusMonths(1);
        YearMonth beforeThat = lastComplete.minusMonths(1);
        YearMonth readFrom = period.compareFrom().isBefore(YearMonth.of(current.getYear() - 1, 1))
            ? period.compareFrom() : YearMonth.of(current.getYear() - 1, 1);

        Map<YearMonth, BigDecimal> monthly = Safely.read("monthly premium collections",
            () -> reporting.monthlyPremiumCollections(readFrom, current).stream()
                .filter(m -> WORKING_CURRENCY.equals(m.currency()))
                .collect(Collectors.toMap(MonthlyAmount::month, MonthlyAmount::amount, BigDecimal::add)));

        BigDecimal collected = monthly == null ? null : monthly.get(lastComplete);
        BigDecimal previous = monthly == null ? null : monthly.get(beforeThat);

        ReportResult sheet = Safely.read("the balance sheet",
            () -> reporting.run("balance-sheet", ReportFilter.month(current)));
        ReportResult income = Safely.read("the income statement",
            () -> reporting.run("income-statement", ReportFilter.months(YearMonth.of(current.getYear(), 1), current)));

        List<ActivityItem> recent = Safely.read("recent activity",
            () -> activity.latest("billing.", "payment.", "finaccounting."));

        // ---- the ranged figures (2026-10-23) ----
        List<MonthPoint> series = monthly == null ? List.of() : series(period.months(), monthly);
        List<MonthPoint> compareSeries = monthly == null ? List.of() : series(period.compareMonths(), monthly);
        BigDecimal collectedNow = monthly == null ? null : total(period.months(), monthly);
        BigDecimal collectedBefore = monthly == null ? null : total(period.compareMonths(), monthly);
        List<Share> byLine = Safely.read("collections by product line", () -> productLines(period));
        Map<YearMonth, Long> issued = Safely.read("policies issued", () ->
            reporting.policiesIssuedByMonth(period.compareFrom(), period.to(), null).stream()
                .collect(Collectors.toMap(MonthlyCount::month, MonthlyCount::count)));
        Long newNow = issued == null ? null : period.months().stream().mapToLong(m -> issued.getOrDefault(m, 0L)).sum();
        Long newBefore = issued == null ? null
            : period.compareMonths().stream().mapToLong(m -> issued.getOrDefault(m, 0L)).sum();
        List<CountPoint> issuedSeries = issued == null ? List.of()
            : period.months().stream().map(m -> new CountPoint(m.toString(), issued.getOrDefault(m, 0L))).toList();
        List<ReceivablesAging> aging = receivables(today);

        return new FinanceDashboardView(
            lastComplete.toString(), Money.of(collected, WORKING_CURRENCY), changePercent(collected, previous),
            current.toString(),
            Money.of(ReportFigures.sectionTotal(sheet, "Assets", "balance"), WORKING_CURRENCY),
            Money.of(ReportFigures.sectionTotal(sheet, "Liabilities", "balance"), WORKING_CURRENCY),
            YearMonth.of(current.getYear(), 1).toString(), current.toString(),
            Money.of(ReportFigures.netResult(income), WORKING_CURRENCY),
            Safely.read("journals awaiting approval", () -> (long) journals.list("SUBMITTED", null, null).size()),
            Safely.read("overdue field receipts",
                () -> billing.searchFieldReceipts("RECONCILIATION_OVERDUE", TOTAL_ONLY).getTotalElements()),
            Safely.read("bank transfers awaiting", () -> (long) payments.listAwaitingEftExecution().size()),
            Safely.read("arrears at the lapse level",
                () -> billing.searchArrears(LAPSE_RECOMMENDATION_LEVEL, false, TOTAL_ONLY).getTotalElements()),
            points(monthly, current.getYear()), points(monthly, current.getYear() - 1), current.toString(),
            aging,
            recent == null ? List.of() : recent,
            period.toView(), Money.of(collectedNow, WORKING_CURRENCY), Money.of(collectedBefore, WORKING_CURRENCY),
            DashboardPeriod.changePercent(collectedNow, collectedBefore), series, compareSeries,
            byLine == null ? List.of() : byLine,
            newNow, newBefore,
            newNow == null ? null : DashboardPeriod.changePercent(BigDecimal.valueOf(newNow), BigDecimal.valueOf(newBefore)),
            issuedSeries, Money.of(outstanding(aging), WORKING_CURRENCY));
    }

    private static List<MonthPoint> series(List<YearMonth> months, Map<YearMonth, BigDecimal> monthly) {
        return months.stream()
            .map(m -> new MonthPoint(m.toString(), Money.of(monthly.getOrDefault(m, BigDecimal.ZERO), WORKING_CURRENCY)))
            .toList();
    }

    private static BigDecimal total(List<YearMonth> months, Map<YearMonth, BigDecimal> monthly) {
        return months.stream().map(m -> monthly.getOrDefault(m, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The working currency's collections per product category, each with its share of their own total. */
    private List<Share> productLines(DashboardPeriod period) {
        List<CategoryAmount> rows = reporting.premiumCollectionsByCategory(period.from(), period.to()).stream()
            .filter(c -> WORKING_CURRENCY.equals(c.currency()))
            .toList();
        BigDecimal whole = rows.stream().map(CategoryAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return rows.stream()
            .map(c -> new Share(c.category(), Money.of(c.amount(), WORKING_CURRENCY), null,
                DashboardPeriod.percentOf(c.amount(), whole)))
            .toList();
    }

    /** The working currency's bands added together; null when billing could not be read. */
    private static BigDecimal outstanding(List<ReceivablesAging> aging) {
        return aging.stream().filter(a -> WORKING_CURRENCY.equals(a.currency())).findFirst()
            .map(a -> a.buckets().stream().map(b -> new BigDecimal(b.outstanding().amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add))
            .orElse(null);
    }

    /**
     * What is owed, aged, as at today -- one entry per currency, every band present.
     *
     * <p>Translated into the dashboard's own shape rather than forwarding billing's: the amount becomes {@link Money}
     * like every other figure on this view, and an empty band keeps its zero rather than disappearing.
     */
    private List<ReceivablesAging> receivables(LocalDate today) {
        List<ReceivablesAging> aged = Safely.read("receivables aging", () ->
            billing.receivablesAging(today).stream()
                .map(a -> new ReceivablesAging(a.currency(), a.buckets().stream()
                    .map(b -> new ReceivablesAging.Bucket(b.name(), b.cases(), Money.of(b.outstanding(), a.currency())))
                    .toList()))
                .toList());
        return aged == null ? List.of() : aged;
    }

    /** A change between two report values -- the only arithmetic here, and it is a difference of two report numbers. */
    static String changePercent(BigDecimal now, BigDecimal before) {
        if (now == null || before == null || before.signum() == 0) {
            return null;
        }
        return now.subtract(before).multiply(BigDecimal.valueOf(100))
            .divide(before, 1, RoundingMode.HALF_UP).toPlainString();
    }

    private static List<MonthPoint> points(Map<YearMonth, BigDecimal> monthly, int year) {
        if (monthly == null) {
            return List.of();
        }
        return monthly.entrySet().stream()
            .filter(e -> e.getKey().getYear() == year)
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new MonthPoint(e.getKey().toString(), Money.of(e.getValue(), WORKING_CURRENCY)))
            .toList();
    }
}
