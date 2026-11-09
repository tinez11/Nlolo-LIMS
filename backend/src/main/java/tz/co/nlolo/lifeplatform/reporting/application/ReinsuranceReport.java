package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.SettlementView;
import tz.co.nlolo.lifeplatform.reinsurance.api.StatementView;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyUtilisationView;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Reinsurance across every treaty (2026-11-09, the reports redesign batch 5): what each treaty holds today, and for the
 * quarters in the chosen months what the statements ceded, earned in commission and recovered -- and what is still owed
 * either way once the settlements recorded against them are taken off. Read from reinsurance's own treaties,
 * utilisation, statements and settlements; the statements themselves stay on their own page.
 */
@Component
public class ReinsuranceReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("reinsurance", "Reinsurance",
        "What each treaty holds today, and for the quarters in these months: premium ceded, commission, recoveries and"
            + " profit commission per statement, and what is still owed either way.",
        ReportCategory.REINSURANCE, DateFilterKind.MONTH_RANGE, List.of(), false);

    private final ReinsuranceApi reinsurance;

    public ReinsuranceReport(ReinsuranceApi reinsurance) {
        this.reinsurance = reinsurance;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        return body(tenantId, f, labels).sections();
    }

    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        List<ReportSection> sections = new ArrayList<>();
        sections.add(treaties());

        List<StatementView> statements = reinsurance.listStatements(null, null).stream()
            .filter(s -> overlaps(s.quarter(), f.monthFrom(), f.monthTo())).toList();
        List<ReportColumn> columns = List.of(col("reinsurer", "Reinsurer", TEXT), col("quarter", "Quarter", TEXT),
            col("status", "Status", TEXT), col("premium", "Premium ceded", MONEY), col("commission", "Commission", MONEY),
            col("recoveries", "Recoveries", MONEY), col("profitCommission", "Profit commission", MONEY),
            col("owedToUs", "Still owed to us", MONEY), col("owedByUs", "Still owed by us", MONEY));
        List<Map<String, String>> rows = new ArrayList<>();
        for (StatementView s : statements) {
            BigDecimal toUs = s.owedToUs();
            BigDecimal byUs = s.owedByUs();
            // An approved statement is settled against: what is left is what the settlements have not covered.
            if ("APPROVED".equals(s.status())) {
                SettlementView.Open open = reinsurance.openSettlement(s.statementId());
                toUs = open.theyOweUs();
                byUs = open.weOweThem();
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("reinsurer", s.reinsurerName());
            row.put("quarter", s.quarter());
            row.put("status", readable(s.status()));
            row.put("premium", Figures.money(s.premium()));
            row.put("commission", Figures.money(s.commission()));
            row.put("recoveries", Figures.money(s.recoveries()));
            row.put("profitCommission", Figures.money(s.profitCommission()));
            row.put("owedToUs", Figures.money(toUs));
            row.put("owedByUs", Figures.money(byUs));
            rows.add(row);
        }
        Map<String, String> totals = Sections.totals(columns, rows);
        sections.add(new ReportSection("Statements for the quarters in " + StatementReports.span(f.monthFrom(), f.monthTo()),
            columns, rows, totals));

        List<ReportFigure> figures = Sections.present(
            Sections.compared("premium", "Premium ceded", totals, null, "MONEY", null, true),
            Sections.compared("recoveries", "Recoveries", totals, null, "MONEY", "UP", true),
            Sections.compared("owedToUs", "Still owed to us", totals, null, "MONEY", "DOWN", true),
            Sections.compared("commission", "Reinsurance commission", totals, null, "MONEY", "UP", false),
            Sections.compared("profitCommission", "Profit commission", totals, null, "MONEY", "UP", false),
            Sections.compared("owedByUs", "Still owed by us", totals, null, "MONEY", null, false));
        return new ReportBody(sections, figures, null, null,
            statements.isEmpty() ? List.of("No statement covers these months yet: statements are prepared once a quarter"
                + " ends.") : List.of());
    }

    /** Every treaty with what it holds today: policies ceded, the sum at risk ceded and the premium ceded a year. */
    private ReportSection treaties() {
        List<ReportColumn> columns = List.of(col("reinsurer", "Reinsurer", TEXT), col("type", "Type", TEXT),
            col("standing", "Standing", TEXT), col("retention", "Retention", MONEY), col("cession", "Ceded %", PERCENT),
            col("commissionRate", "Commission %", PERCENT), col("policies", "Policies ceded", COUNT),
            col("sumCeded", "Sum at risk ceded", MONEY), col("premiumCeded", "Premium ceded", MONEY));
        List<Map<String, String>> rows = new ArrayList<>();
        for (TreatyView t : reinsurance.listTreaties(null)) {
            TreatyUtilisationView u = reinsurance.getTreatyUtilisation(t.treatyId());
            Map<String, String> row = new LinkedHashMap<>();
            row.put("reinsurer", t.reinsurerName());
            row.put("type", readable(t.treatyType().name()));
            row.put("standing", t.standing() == null ? readable(t.status().name()) : readable(t.standing().name()));
            row.put("retention", Figures.money(t.retentionLimitAmount()));
            row.put("cession", t.cessionPercent() == null ? "" : t.cessionPercent().stripTrailingZeros().toPlainString());
            row.put("commissionRate", t.commissionPercent() == null ? "" : t.commissionPercent().stripTrailingZeros().toPlainString());
            row.put("policies", Figures.count(u.cessionCount()));
            row.put("sumCeded", Figures.money(u.cededAmount()));
            row.put("premiumCeded", Figures.money(u.cededPremiumAmount()));
            rows.add(row);
        }
        // A retention is each treaty's own limit: added across treaties it is a number that means nothing.
        Map<String, String> totals = new LinkedHashMap<>(Sections.totals(columns, rows));
        if (!totals.isEmpty()) totals.put("retention", "");
        return new ReportSection("Treaties today", columns, rows, totals);
    }

    /** Whether a quarter (YYYY-Qn) has a month inside from..to. */
    static boolean overlaps(String quarter, YearMonth from, YearMonth to) {
        if (quarter == null || !quarter.matches("\\d{4}-Q[1-4]")) return false;
        int year = Integer.parseInt(quarter.substring(0, 4));
        int q = quarter.charAt(6) - '0';
        YearMonth first = YearMonth.of(year, (q - 1) * 3 + 1);
        YearMonth last = first.plusMonths(2);
        return !last.isBefore(from) && !first.isAfter(to);
    }

    /** "PENDING_APPROVAL" reads "Pending approval". */
    static String readable(String code) {
        if (code == null || code.isEmpty()) return "";
        String words = code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
