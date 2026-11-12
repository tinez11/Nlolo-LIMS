package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactorSetView;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Shock;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;

/**
 * The capital adequacy ratio re-run under each named shock in the factor set, worst first (2026-11-09, the reports
 * redesign batch 3): equities falling, rates moving, more deaths, more lapses, a reinsurer failing, a catastrophe.
 *
 * <p>An approximation, and it says so: an asset shock moves the balances it names, and mortality and lapse shocks
 * scale the insurance-risk charge. Re-projecting the contracts themselves under a shock is the IFRS 17 engine's work.
 */
@Component
public class StressTestReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("stress-tests", "Stress tests",
        "The capital adequacy ratio under each named shock -- markets, rates, deaths, lapses, a reinsurer failing, a"
            + " catastrophe -- worst first, against the minimum.",
        ReportCategory.SOLVENCY, DateFilterKind.MONTH, List.of(), false).from("LEDGER");

    static final String APPROXIMATION = "An approximation: an asset shock moves the account balances it names, and a"
        + " mortality or lapse shock scales the insurance-risk charge. The contracts themselves are not re-projected.";

    private final SolvencyReports solvency;

    public StressTestReport(SolvencyReports solvency) {
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

    private record Outcome(Shock shock, Solvency.Capital capital) {}

    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        YearMonth month = f.monthFrom();
        SolvencyFactorSetView set = solvency.factors(tenantId, month);
        SolvencyFactors factors = set.factors();
        Solvency.Book book = solvency.book(month);
        Solvency.Capital base = Solvency.compute(book, factors);
        BigDecimal minimum = minimum(factors);

        List<Outcome> outcomes = new ArrayList<>();
        for (Shock shock : factors.shocks()) outcomes.add(new Outcome(shock, Solvency.compute(book, factors, shock)));
        // Worst first, by the capital left over required capital after the shock. Not by the ratio: when capital is
        // already negative, a shock that raises required capital makes the ratio LESS negative, and would rank as
        // good news. What is left over only ever falls as a shock bites.
        outcomes.sort(Comparator.comparing((Outcome o) -> over(o.capital())));

        List<ReportColumn> columns = List.of(new ReportColumn("rank", "Rank", TEXT),
            new ReportColumn("shock", "Shock", TEXT), new ReportColumn("acts", "What it does", TEXT),
            new ReportColumn("available", "Available capital", MONEY), new ReportColumn("required", "Required capital", MONEY),
            new ReportColumn("over", "Over (or short of) required", MONEY),
            new ReportColumn("ratio", "Ratio after", PERCENT), new ReportColumn("change", "Change in ratio", TEXT),
            new ReportColumn("minimum", "Minimum met", TEXT));
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("", "Before any shock", "The month as it stands", base, base, minimum));
        int rank = 1;
        for (Outcome o : outcomes) {
            rows.add(row(Integer.toString(rank++), o.shock().label(), describe(o.shock()), o.capital(), base, minimum));
        }

        long kept = outcomes.stream().filter(o -> meets(o.capital(), minimum)).count();
        Outcome worst = outcomes.isEmpty() ? null : outcomes.get(0);
        List<ReportFigure> figures = new ArrayList<>();
        figures.add(ClaimsReporting.ratio("ratio", "Capital adequacy ratio now", base.ratio(), null, "UP", "PERCENT", true));
        if (worst != null) {
            figures.add(ClaimsReporting.ratio("worst", "Worst case: " + worst.shock().label(), worst.capital().ratio(),
                null, "UP", "PERCENT", true));
            figures.add(new ReportFigure("kept", "Shocks the minimum survives, of " + outcomes.size(),
                Long.toString(kept), null, null, null, null, true, "COUNT"));
        }

        List<ReportSection> sections = List.of(
            new ReportSection("Shocks at end of " + StatementReports.span(month, month) + ", worst first", columns, rows,
                Map.of()),
            SolvencyReports.factorSection(set));
        return new ReportBody(sections, figures, null, null, SolvencyReports.notices(set, APPROXIMATION));
    }

    private static Map<String, String> row(String rank, String shock, String acts, Solvency.Capital after,
                                           Solvency.Capital base, BigDecimal minimum) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("rank", rank);
        row.put("shock", shock);
        row.put("acts", acts);
        row.put("available", Figures.money(after.available().value()));
        row.put("required", Figures.money(after.required().value()));
        row.put("over", Figures.money(over(after)));
        row.put("ratio", after.ratio() == null ? "" : after.ratio().toPlainString());
        row.put("change", after == base || after.ratio() == null || base.ratio() == null ? ""
            : points(after.ratio().subtract(base.ratio())));
        row.put("minimum", after.ratio() == null ? "" : meets(after, minimum) ? "Met" : "NOT met");
        return row;
    }

    static BigDecimal over(Solvency.Capital c) {
        return c.available().value().subtract(c.required().value());
    }

    /** "+35.3 pts", "-12.0 pts", "no change": a change in a ratio is in percentage points, not per cent. */
    private static String points(BigDecimal change) {
        if (change.signum() == 0) return "no change";
        return (change.signum() > 0 ? "+" : "") + change.toPlainString() + " pts";
    }

    /** The lowest threshold is the minimum: below it a company may not write business. */
    static BigDecimal minimum(SolvencyFactors factors) {
        return factors.thresholds().stream().map(SolvencyFactors.Threshold::ratioPercent).min(Comparator.naturalOrder())
            .orElse(BigDecimal.valueOf(100));
    }

    private static boolean meets(Solvency.Capital c, BigDecimal minimum) {
        return c.ratio() != null && c.ratio().compareTo(minimum) >= 0;
    }

    private static String describe(Shock s) {
        return switch (s.kind()) {
            case Shock.ASSETS -> "Accounts " + String.join(", ", s.accounts()) + " move " + signed(s.percent());
            case Shock.INSURANCE -> "Insurance-risk charge " + signed(s.percent());
            case Shock.LOSS -> "A loss of " + Statement.money(s.amount()) + " on capital";
            default -> "";
        };
    }

    private static String signed(BigDecimal percent) {
        return (percent.signum() > 0 ? "+" : "") + Solvency.rate(percent);
    }
}
