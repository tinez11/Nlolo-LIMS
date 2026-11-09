package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactorSetView;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;

/**
 * Premium, claims, the result and the capital adequacy ratio for the twelve months after the chosen one (2026-11-09,
 * the reports redesign batch 3). A TREND forecast, and labelled so: a straight line through the last 24 months of the
 * ledger, with a low-to-high range of one standard deviation of the months around it. It knows nothing the ledger does
 * not -- no planned launch, no price change.
 *
 * <p>The ratio carries forward: each month's forecast result is added to available capital (the low and high results
 * to the low and high capital), and the operational charge is re-read on the premium of the twelve months to that
 * month. The other risk charges are held where they stand, for want of a projected balance sheet.
 */
@Component
public class SolvencyForecastReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("solvency-forecast", "Forecast",
        "Premium, claims, the result and the capital adequacy ratio for the next 12 months: a trend from the last 24"
            + " months of the ledger, with a low-to-high range.",
        ReportCategory.SOLVENCY, DateFilterKind.MONTH, List.of(), false);

    static final int HISTORY = 24;
    static final int AHEAD = 12;
    private static final List<String> RESULT_CLASSES = List.of("4000", "5000", "6000", "7000", "8000");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TICK = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

    private final FinaccountingApi finaccounting;
    private final SolvencyReports solvency;

    public SolvencyForecastReport(FinaccountingApi finaccounting, SolvencyReports solvency) {
        this.finaccounting = finaccounting;
        this.solvency = solvency;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        return body(tenantId, f, labels).sections();
    }

    /** One month of the ledger: what came in, what the insurance service cost, and the whole result. */
    record Month(YearMonth month, BigDecimal premium, BigDecimal claims, BigDecimal result) {
        boolean empty() {
            return premium.signum() == 0 && claims.signum() == 0 && result.signum() == 0;
        }
    }

    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        YearMonth last = f.monthFrom();
        List<Month> history = new ArrayList<>();
        for (int i = HISTORY - 1; i >= 0; i--) history.add(month(last.minusMonths(i)));
        // The months before the book began are not a trend of zero: the line is drawn from the first month with any.
        while (!history.isEmpty() && history.get(0).empty()) history.remove(0);

        Trend premium = Trend.fit(history.stream().map(Month::premium).toList());
        Trend claims = Trend.fit(history.stream().map(Month::claims).toList());
        Trend result = Trend.fit(history.stream().map(Month::result).toList());

        SolvencyFactorSetView set = solvency.factors(tenantId, last);
        Solvency.Book book = solvency.book(last);
        Solvency.Capital now = Solvency.compute(book, set.factors());
        BigDecimal otherCharges = now.required().value().subtract(now.operational());

        List<Map<String, String>> rows = new ArrayList<>();
        BigDecimal capitalLow = now.available().value();
        BigDecimal capitalMid = capitalLow;
        BigDecimal capitalHigh = capitalLow;
        BigDecimal premiumTotal = BigDecimal.ZERO;
        BigDecimal resultTotal = BigDecimal.ZERO;
        BigDecimal ratioLow = null;
        BigDecimal ratioMid = null;
        BigDecimal ratioHigh = null;
        List<BigDecimal> forecastPremium = new ArrayList<>();
        for (int k = 1; k <= AHEAD; k++) {
            BigDecimal p = premium.ahead(k).max(BigDecimal.ZERO);
            BigDecimal c = claims.ahead(k).max(BigDecimal.ZERO);
            BigDecimal r = result.ahead(k);
            BigDecimal band = result.spreadCents();
            forecastPremium.add(p);
            premiumTotal = premiumTotal.add(p);
            resultTotal = resultTotal.add(r);
            capitalMid = capitalMid.add(r);
            capitalLow = capitalLow.add(r).subtract(band);
            capitalHigh = capitalHigh.add(r).add(band);

            // The operational charge on the twelve months to this one: the rest of the actual year, then the forecast.
            BigDecimal year = forecastPremium.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            for (int j = 0; j < AHEAD - k && j < history.size(); j++) {
                year = year.add(history.get(history.size() - 1 - j).premium());
            }
            BigDecimal operational = Solvency.cents(year.max(BigDecimal.ZERO).multiply(set.factors().operationalPercent())
                .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64));
            BigDecimal required = otherCharges.add(operational);
            ratioLow = ratio(capitalLow, required);
            ratioMid = ratio(capitalMid, required);
            ratioHigh = ratio(capitalHigh, required);

            Map<String, String> row = new LinkedHashMap<>();
            row.put("month", last.plusMonths(k).format(MONTH));
            row.put("premium", Figures.money(p));
            row.put("claims", Figures.money(c));
            row.put("result", Figures.money(r));
            row.put("ratioLow", text(ratioLow));
            row.put("ratio", text(ratioMid));
            row.put("ratioHigh", text(ratioHigh));
            rows.add(row);
        }

        List<ReportColumn> ahead = List.of(new ReportColumn("month", "Month", TEXT),
            new ReportColumn("premium", "Premium", MONEY), new ReportColumn("claims", "Claims and service costs", MONEY),
            new ReportColumn("result", "Result", MONEY), new ReportColumn("ratioLow", "Ratio, low", PERCENT),
            new ReportColumn("ratio", "Ratio, central", PERCENT), new ReportColumn("ratioHigh", "Ratio, high", PERCENT));
        List<ReportColumn> past = List.of(new ReportColumn("month", "Month", TEXT),
            new ReportColumn("premium", "Premium", MONEY), new ReportColumn("claims", "Claims and service costs", MONEY),
            new ReportColumn("result", "Result", MONEY));
        List<Map<String, String>> pastRows = new ArrayList<>();
        for (Month m : history) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("month", m.month().format(MONTH));
            row.put("premium", Figures.money(m.premium()));
            row.put("claims", Figures.money(m.claims()));
            row.put("result", Figures.money(m.result()));
            pastRows.add(row);
        }

        List<ReportSection> sections = List.of(
            new ReportSection("The next 12 months", ahead, rows, Map.of()),
            new ReportSection("The " + history.size() + " months the trend is drawn from", past, pastRows, Map.of()),
            SolvencyReports.factorSection(set));

        List<ReportFigure> figures = List.of(
            ClaimsReporting.money("premium", "Premium, next 12 months", premiumTotal, null, "UP", true),
            ClaimsReporting.money("result", "Result, next 12 months", resultTotal, null, "UP", true),
            ClaimsReporting.ratio("ratio", "Capital adequacy ratio in 12 months", ratioMid, null, "UP", "PERCENT", true),
            ClaimsReporting.ratio("ratioNow", "Ratio now", now.ratio(), null, "UP", "PERCENT", false),
            ClaimsReporting.ratio("ratioLow", "Ratio in 12 months, low", ratioLow, null, "UP", "PERCENT", false),
            ClaimsReporting.ratio("ratioHigh", "Ratio in 12 months, high", ratioHigh, null, "UP", "PERCENT", false));

        String method = "Trend forecast: a straight line through the last " + history.size() + " months of the ledger,"
            + " with a range of one standard deviation of the months around it. It knows nothing the ledger does not.";
        List<String> notices = history.size() < 6
            ? SolvencyReports.notices(set, method, "Only " + history.size() + " months of history: read the trend as a"
                + " rough guide.")
            : SolvencyReports.notices(set, method);
        return new ReportBody(sections, figures, chart(history, rows, last), null, notices);
    }

    /** Premium and claims, the months behind as solid lines and the months ahead dashed, joined at the last actual. */
    private static ReportChart chart(List<Month> history, List<Map<String, String>> ahead, YearMonth last) {
        List<ReportChart.Point> premium = new ArrayList<>();
        List<ReportChart.Point> premiumAhead = new ArrayList<>();
        List<ReportChart.Point> claims = new ArrayList<>();
        List<ReportChart.Point> claimsAhead = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            Month m = history.get(i);
            String period = m.month().format(TICK);
            boolean join = i == history.size() - 1;
            premium.add(new ReportChart.Point(period, Figures.money(m.premium())));
            claims.add(new ReportChart.Point(period, Figures.money(m.claims())));
            premiumAhead.add(new ReportChart.Point(period, join ? Figures.money(m.premium()) : null));
            claimsAhead.add(new ReportChart.Point(period, join ? Figures.money(m.claims()) : null));
        }
        for (int k = 0; k < ahead.size(); k++) {
            String period = last.plusMonths(k + 1L).format(TICK);
            premium.add(new ReportChart.Point(period, null));
            claims.add(new ReportChart.Point(period, null));
            premiumAhead.add(new ReportChart.Point(period, ahead.get(k).get("premium")));
            claimsAhead.add(new ReportChart.Point(period, ahead.get(k).get("claims")));
        }
        return new ReportChart("TREND", "Premium and claims, month by month: the months behind, then the trend ahead",
            List.of(), List.of(new ReportChart.Series("Premium", false, premium),
                new ReportChart.Series("Premium, trend", true, premiumAhead),
                new ReportChart.Series("Claims and service costs", false, claims),
                new ReportChart.Series("Claims, trend", true, claimsAhead)));
    }

    private Month month(YearMonth m) {
        var view = finaccounting.trialBalance(m.toString());
        List<AccountBalanceView> ledger = view == null ? List.of() : view.accounts();
        BigDecimal result = BigDecimal.ZERO;
        for (AccountBalanceView a : ledger) {
            if (RESULT_CLASSES.contains(a.accountCode())) result = result.add(a.credit().subtract(a.debit()));
        }
        return new Month(m, LedgerAccounts.classTotal(ledger, "4000"), LedgerAccounts.classTotal(ledger, "5000"), result);
    }

    private static BigDecimal ratio(BigDecimal available, BigDecimal required) {
        return required.signum() <= 0 ? null
            : available.multiply(BigDecimal.valueOf(100)).divide(required, 1, RoundingMode.HALF_UP);
    }

    private static String text(BigDecimal ratio) {
        return ratio == null ? "" : ratio.toPlainString();
    }
}
