package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionAccrualView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Commission across every agent (2026-11-09, the reports redesign batch 5; the gap the commission chain trace found:
 * nothing showed earned, clawed back and paid together). Read from distribution's own accruals and statements -- the
 * same rows the agent pages and the ledger postings come from -- for a span of months:
 *
 * <ul>
 *   <li><b>earned to paid</b>: first-year, renewal, overrides and bonuses, less clawbacks, is commission booked; less
 *   what the months' statements have paid, is what is booked and not yet paid;</li>
 *   <li>the same by agent, and by product against the premium collected (commission ÷ premium);</li>
 *   <li>the statements not yet paid, by how long since their month closed.</li>
 * </ul>
 *
 * <p>A clawback is an accrual that reverses another, or any negative accrual (a lapse or a free-look cancellation
 * inside the clawback window). Agent statements stay on the agent's page.
 */
@Component
public class CommissionsReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("commissions", "Commissions",
        "Commission earned, clawed back and paid across every agent, by agent and by product against the premium"
            + " collected, and the statements not yet paid, by age.",
        ReportCategory.COMMISSIONS, DateFilterKind.MONTH_RANGE, List.of(), false).comparingPeriods();

    private final DistributionApi distribution;
    private final ReportQueries queries;
    private final Clock clock;

    public CommissionsReport(DistributionApi distribution, ReportQueries queries, ReportingClock clock) {
        this.distribution = distribution;
        this.queries = queries;
        this.clock = clock.clock();
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    /** The months' commission, in the tiers a statement reads it. */
    static final class Tally {
        BigDecimal firstYear = BigDecimal.ZERO;
        BigDecimal renewal = BigDecimal.ZERO;
        BigDecimal overrides = BigDecimal.ZERO;
        BigDecimal bonuses = BigDecimal.ZERO;
        BigDecimal clawbacks = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;

        void add(CommissionAccrualView a) {
            if (clawback(a)) {
                clawbacks = clawbacks.add(a.amount().abs());
                return;
            }
            switch (a.tierType()) {
                case FIRST_YEAR -> firstYear = firstYear.add(a.amount());
                case RENEWAL -> renewal = renewal.add(a.amount());
                case OVERRIDE, SUPERVISOR_OVERRIDE -> overrides = overrides.add(a.amount());
                case THRESHOLD_BONUS -> bonuses = bonuses.add(a.amount());
            }
        }

        BigDecimal booked() {
            return firstYear.add(renewal).add(overrides).add(bonuses).subtract(clawbacks);
        }

        BigDecimal unpaid() {
            return booked().subtract(paid);
        }
    }

    static boolean clawback(CommissionAccrualView a) {
        return a.reversesAccrualId() != null || a.amount().signum() < 0;
    }

    private record Months(YearMonth from, YearMonth to) {
        String label() {
            return StatementReports.span(from, to);
        }
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        return body(tenantId, f, labels).sections();
    }

    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        Months now = new Months(f.monthFrom(), f.monthTo());
        YearMonth[] compared = f.comparisonMonths();
        List<CommissionAccrualView> accruals = distribution.listAccrualsBetween(now.from().toString(), now.to().toString());
        List<CommissionStatementView> statements = distribution.listStatementsBetween(now.from().toString(), now.to().toString());

        Tally all = new Tally();
        Map<UUID, Tally> byAgent = new LinkedHashMap<>();
        Map<String, BigDecimal> byProduct = new TreeMap<>();
        for (CommissionAccrualView a : accruals) {
            all.add(a);
            byAgent.computeIfAbsent(a.agentId(), k -> new Tally()).add(a);
            byProduct.merge(a.productId() == null ? "" : a.productId().toString(),
                clawback(a) ? a.amount().abs().negate() : a.amount(), BigDecimal::add);
        }
        for (CommissionStatementView s : statements) {
            if (s.status() == StatementStatus.PAID) {
                all.paid = all.paid.add(s.totalAmount());
                byAgent.computeIfAbsent(s.agentId(), k -> new Tally()).paid =
                    byAgent.get(s.agentId()).paid.add(s.totalAmount());
            }
        }

        Tally before = null;
        if (compared != null) {
            before = new Tally();
            for (CommissionAccrualView a : distribution.listAccrualsBetween(compared[0].toString(), compared[1].toString())) {
                before.add(a);
            }
            for (CommissionStatementView s : distribution.listStatementsBetween(compared[0].toString(), compared[1].toString())) {
                if (s.status() == StatementStatus.PAID) before.paid = before.paid.add(s.totalAmount());
            }
        }

        Map<String, BigDecimal> premium = queries.collectedByProduct(tenantId, now.from().atDay(1), now.to().atEndOfMonth(),
            ReportFilter.none());
        BigDecimal premiumTotal = premium.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ReportSection> sections = List.of(
            movement(all, now),
            agents(byAgent, labels),
            products(byProduct, premium, labels),
            unpaid(statements, CivilDates.today(clock)));

        List<tz.co.nlolo.lifeplatform.reporting.api.ReportFigure> figures = new ArrayList<>(List.of(
            ClaimsReporting.money("booked", "Commission booked", all.booked(), before == null ? null : before.booked(),
                null, true),
            ClaimsReporting.money("paid", "Paid on statements", all.paid, before == null ? null : before.paid, null, true),
            ClaimsReporting.money("unpaid", "Booked, not yet paid", all.unpaid(), before == null ? null : before.unpaid(),
                "DOWN", true),
            ClaimsReporting.money("clawbacks", "Clawed back", all.clawbacks, before == null ? null : before.clawbacks,
                "DOWN", false),
            ClaimsReporting.money("overrides", "Overrides", all.overrides, before == null ? null : before.overrides, null,
                false)));
        if (premiumTotal.signum() > 0) {
            figures.add(ClaimsReporting.ratio("ratio", "Commission ÷ premium collected",
                all.booked().multiply(BigDecimal.valueOf(100)).divide(premiumTotal, 1, java.math.RoundingMode.HALF_UP),
                null, null, "PERCENT", false));
        }
        return new ReportBody(sections, figures, chart(all),
            compared == null ? null : "compared with " + StatementReports.span(compared[0], compared[1]));
    }

    private static ReportSection movement(Tally t, Months months) {
        List<ReportColumn> columns = List.of(col("line", "Commission", TEXT), col("amount", "Amount", MONEY));
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(line("First-year commission", t.firstYear, "LINE"));
        rows.add(line("Renewal commission", t.renewal, "LINE"));
        rows.add(line("Overrides (supervisors and managers)", t.overrides, "LINE"));
        rows.add(line("Bonuses", t.bonuses, "LINE"));
        rows.add(line("Clawed back (lapses and free-look cancellations)", t.clawbacks.negate(), "LINE"));
        rows.add(line("Commission booked", t.booked(), "SUBTOTAL"));
        rows.add(line("Paid on these months' statements", t.paid.negate(), "LINE"));
        rows.add(line("Booked, not yet paid", t.unpaid(), "TOTAL"));
        return new ReportSection("Earned to paid, " + months.label(), columns, rows, Map.of());
    }

    private static Map<String, String> line(String label, BigDecimal amount, String kind) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("line", label);
        row.put("amount", Figures.money(amount));
        row.put("_kind", kind);
        return row;
    }

    private static ReportSection agents(Map<UUID, Tally> byAgent, Labels labels) {
        List<ReportColumn> columns = List.of(col("agent", "Agent", TEXT), col("firstYear", "First year", MONEY),
            col("renewal", "Renewal", MONEY), col("overrides", "Overrides", MONEY), col("clawbacks", "Clawed back", MONEY),
            col("booked", "Booked", MONEY), col("paid", "Paid", MONEY), col("unpaid", "Not yet paid", MONEY));
        List<Map<String, String>> rows = new ArrayList<>();
        byAgent.forEach((agentId, t) -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("agent", agentId == null ? "No agent" : labels.dimension(GroupBy.AGENT, agentId.toString()));
            row.put("firstYear", Figures.money(t.firstYear));
            row.put("renewal", Figures.money(t.renewal));
            row.put("overrides", Figures.money(t.overrides.add(t.bonuses)));
            row.put("clawbacks", Figures.money(t.clawbacks.negate()));
            row.put("booked", Figures.money(t.booked()));
            row.put("paid", Figures.money(t.paid));
            row.put("unpaid", Figures.money(t.unpaid()));
            rows.add(row);
        });
        rows.sort(Comparator.comparing((Map<String, String> r) -> new BigDecimal(r.get("booked"))).reversed());
        return new ReportSection("By agent", columns, rows, Sections.totals(columns, rows));
    }

    private static ReportSection products(Map<String, BigDecimal> byProduct, Map<String, BigDecimal> premium,
                                          Labels labels) {
        List<ReportColumn> columns = List.of(col("product", "Product", TEXT), col("commission", "Commission booked", MONEY),
            col("premium", "Premium collected", MONEY), col("ratio", "Commission ÷ premium %", PERCENT));
        List<Map<String, String>> rows = new ArrayList<>();
        byProduct.forEach((productId, commission) -> {
            BigDecimal collected = premium.getOrDefault(productId, BigDecimal.ZERO);
            Map<String, String> row = new LinkedHashMap<>();
            row.put("product", productId.isEmpty() ? "Product not known" : labels.dimension(GroupBy.PRODUCT, productId));
            row.put("commission", Figures.money(commission));
            row.put("premium", Figures.money(collected));
            row.put("ratio", Figures.percent(commission, collected));
            rows.add(row);
        });
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("product")));
        Map<String, String> totals = new LinkedHashMap<>(Sections.totals(columns, rows));
        if (!totals.isEmpty()) {
            totals.put("ratio", Figures.percent(new BigDecimal(totals.get("commission")), new BigDecimal(totals.get("premium"))));
        }
        return new ReportSection("By product, against the premium collected", columns, rows, totals);
    }

    static final List<String> AGES = List.of("This month or last", "2 to 3 months", "4 to 6 months", "Over 6 months");

    /** Statements with money on them that are not paid, by how many months since their month ended. */
    static ReportSection unpaid(List<CommissionStatementView> statements, LocalDate today) {
        long[] counts = new long[4];
        BigDecimal[] amounts = { BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO };
        for (CommissionStatementView s : statements) {
            if (s.status() == StatementStatus.PAID || s.totalAmount() == null || s.totalAmount().signum() <= 0) continue;
            long months = ChronoUnit.MONTHS.between(YearMonth.parse(s.period()), YearMonth.from(today));
            int band = months <= 1 ? 0 : months <= 3 ? 1 : months <= 6 ? 2 : 3;
            counts[band]++;
            amounts[band] = amounts[band].add(s.totalAmount());
        }
        List<ReportColumn> columns = List.of(col("age", "Since the statement's month", TEXT),
            col("statements", "Statements", COUNT), col("amount", "Not yet paid", MONEY));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("age", AGES.get(i));
            row.put("statements", Figures.count(counts[i]));
            row.put("amount", Figures.money(amounts[i]));
            rows.add(row);
        }
        return new ReportSection("Statements not yet paid, by age", columns, rows, Sections.totals(columns, rows));
    }

    private static ReportChart chart(Tally t) {
        List<ReportChart.Bar> bars = new ArrayList<>();
        bars.add(new ReportChart.Bar("First year", Figures.money(t.firstYear), "START"));
        bars.add(new ReportChart.Bar("Renewal", Figures.money(t.renewal), "UP"));
        bars.add(new ReportChart.Bar("Overrides and bonuses", Figures.money(t.overrides.add(t.bonuses)), "UP"));
        bars.add(new ReportChart.Bar("Clawed back", Figures.money(t.clawbacks), "DOWN"));
        bars.add(new ReportChart.Bar("Paid", Figures.money(t.paid), "DOWN"));
        bars.add(new ReportChart.Bar("Not yet paid", Figures.money(t.unpaid()), "END"));
        return new ReportChart("BRIDGE", "Commission from earned to paid", bars);
    }
}
