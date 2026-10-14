package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView.MonthPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
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

    public FinanceDashboard(ReportingApi reporting, ManualJournalApi journals, BillingApi billing, PaymentApi payments) {
        this.reporting = reporting;
        this.journals = journals;
        this.billing = billing;
        this.payments = payments;
    }

    /** @param today the civil date in Africa/Dar_es_Salaam, supplied by the caller */
    public FinanceDashboardView build(LocalDate today) {
        YearMonth current = YearMonth.from(today);
        YearMonth lastComplete = current.minusMonths(1);
        YearMonth beforeThat = lastComplete.minusMonths(1);

        Map<YearMonth, BigDecimal> monthly = Safely.read("monthly premium collections",
            () -> reporting.monthlyPremiumCollections(YearMonth.of(current.getYear() - 1, 1), current).stream()
                .filter(m -> WORKING_CURRENCY.equals(m.currency()))
                .collect(Collectors.toMap(MonthlyAmount::month, MonthlyAmount::amount, BigDecimal::add)));

        BigDecimal collected = monthly == null ? null : monthly.get(lastComplete);
        BigDecimal previous = monthly == null ? null : monthly.get(beforeThat);

        ReportResult sheet = Safely.read("the balance sheet",
            () -> reporting.run("balance-sheet", ReportFilter.month(current)));
        ReportResult income = Safely.read("the income statement",
            () -> reporting.run("income-statement", ReportFilter.months(YearMonth.of(current.getYear(), 1), current)));

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
            points(monthly, current.getYear()), points(monthly, current.getYear() - 1), current.toString());
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
