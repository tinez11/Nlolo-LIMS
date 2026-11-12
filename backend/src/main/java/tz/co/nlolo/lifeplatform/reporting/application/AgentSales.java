package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.reporting.api.CompareWith;
import tz.co.nlolo.lifeplatform.reporting.api.PolicySold;
import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.SalesTotals;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * An agent's sales in money (2026-11-12, analytics plan 3; the user: "premium sold = the yearly premium"). Counted on
 * the day cover started (D1); single premiums apart (D2); savings count their opening deposit, apart (D3). Scope is
 * the agent's team from their own party -- never a parameter -- and "Me" may name only an agent inside it.
 *
 * <p>In TZS, the currency every premium is sold in today: a figure in another currency is left out of the totals rather
 * than added to shillings.
 */
@Service
public class AgentSales {

    static final List<String> BY = List.of("MONTH", "QUARTER", "YEAR");
    private static final String CURRENCY = "TZS";

    private final ReportQueries queries;
    private final DistributionApi distribution;
    private final LabelsFactory labelsFactory;
    private final ReportingClock clock;

    public AgentSales(ReportQueries queries, DistributionApi distribution, LabelsFactory labelsFactory, ReportingClock clock) {
        this.queries = queries;
        this.distribution = distribution;
        this.labelsFactory = labelsFactory;
        this.clock = clock;
    }

    /** The agents whose sales these are: the team, or one agent inside it. */
    Set<UUID> scope(UUID agentPartyId, UUID onlyAgentId) {
        Set<UUID> team = new HashSet<>(distribution.resolveAgentTeam(agentPartyId));
        if (onlyAgentId == null) return team;
        if (!team.contains(onlyAgentId)) {
            throw new AccessDeniedException("Access denied: that agent is not in your team");
        }
        return Set.of(onlyAgentId);
    }

    public List<SalesTotals> byPeriod(UUID agentPartyId, UUID onlyAgentId, LocalDate from, LocalDate to, String by) {
        validate(from, to, by);
        return queries.salesTotals(TenantContext.get(), from, to, scope(agentPartyId, onlyAgentId), by).stream()
            .filter(s -> CURRENCY.equals(s.currency())).toList();
    }

    public List<PolicySold> policies(UUID agentPartyId, UUID onlyAgentId, LocalDate from, LocalDate to) {
        validate(from, to, "MONTH");
        Labels labels = labelsFactory.create();
        return queries.policiesSold(TenantContext.get(), from, to, scope(agentPartyId, onlyAgentId)).stream()
            .map(r -> new PolicySold(r.policyNumber(), labels.product(r.productId()), r.clientName(), r.onCoverFrom(),
                Labels.words(r.frequency()), r.premium(), r.savings() ? null : r.premiumAYear(), r.cover(),
                r.savings() ? r.premium() : null, r.currency(),
                r.agentId() == null ? null : labels.agent(r.agentId())))
            .toList();
    }

    /** "My sales": every bucket in the span, its totals, the four money figures compared, and a trend of premium a year. */
    public ReportResult report(UUID agentPartyId, UUID onlyAgentId, LocalDate from, LocalDate to, String by,
                               CompareWith compareWith) {
        List<SalesTotals> now = byPeriod(agentPartyId, onlyAgentId, from, to, by);
        LocalDate[] before = ReportFilter.period(from, to).withCompareWith(compareWith).comparisonSpan();
        SalesTotals totalNow = sum(now);
        SalesTotals totalBefore = before == null ? null : sum(byPeriod(agentPartyId, onlyAgentId, before[0], before[1], by));

        List<ReportColumn> columns = List.of(col("period", "Period", TEXT), col("policies", "Policies sold", COUNT),
            col("premiumAYear", "Premium a year", MONEY), col("singlePremiums", "Single premiums", MONEY),
            col("cover", "Cover sold", MONEY), col("savingsDeposits", "Savings deposits taken", MONEY));
        List<Map<String, String>> rows = new ArrayList<>();
        for (SalesTotals s : now) rows.add(row(words(s.bucket()), s));
        Map<String, String> totals = rows.isEmpty() ? Map.of() : row("Total", totalNow);

        List<ReportFigure> figures = List.of(
            ClaimsReporting.money("premiumAYear", "Premium sold a year", totalNow.premiumAYear(),
                totalBefore == null ? null : totalBefore.premiumAYear(), "UP", true),
            ClaimsReporting.money("cover", "Cover sold", totalNow.cover(),
                totalBefore == null ? null : totalBefore.cover(), "UP", true),
            ClaimsReporting.money("singlePremiums", "Single premiums sold", totalNow.singlePremiums(),
                totalBefore == null ? null : totalBefore.singlePremiums(), "UP", false),
            ClaimsReporting.money("savingsDeposits", "Savings deposits taken", totalNow.savingsDeposits(),
                totalBefore == null ? null : totalBefore.savingsDeposits(), "UP", false));

        String span = from + " to " + to;
        return new ReportResult("my-sales", "My sales", span + " · by " + by.toLowerCase(java.util.Locale.ROOT)
                + " · sold on the day cover started",
            Instant.now(clock.clock()),
            List.of(new ReportSection("Sales by " + by.toLowerCase(java.util.Locale.ROOT), columns, rows, totals)),
            figures,
            // Premium sold a year, period by period: the shape of the agent's selling over the span.
            new ReportChart("TREND", "Premium sold a year, by " + by.toLowerCase(java.util.Locale.ROOT), List.of(),
                List.of(new ReportChart.Series("Premium sold a year", false, now.stream()
                    .map(s -> new ReportChart.Point(words(s.bucket()), Figures.money(s.premiumAYear()))).toList()))),
            before == null ? null : "compared with " + before[0] + " to " + before[1], List.of(),
            queries.lastAppliedAt(TenantContext.get()));
    }

    private static Map<String, String> row(String label, SalesTotals s) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("period", label);
        row.put("policies", Figures.count(s.policies()));
        row.put("premiumAYear", Figures.money(s.premiumAYear()));
        row.put("singlePremiums", Figures.money(s.singlePremiums()));
        row.put("cover", Figures.money(s.cover()));
        row.put("savingsDeposits", Figures.money(s.savingsDeposits()));
        return row;
    }

    private static SalesTotals sum(List<SalesTotals> rows) {
        long n = 0;
        BigDecimal premium = BigDecimal.ZERO, single = BigDecimal.ZERO, cover = BigDecimal.ZERO, savings = BigDecimal.ZERO;
        for (SalesTotals s : rows) {
            n += s.policies();
            premium = premium.add(s.premiumAYear());
            single = single.add(s.singlePremiums());
            cover = cover.add(s.cover());
            savings = savings.add(s.savingsDeposits());
        }
        return new SalesTotals("total", CURRENCY, n, premium, single, cover, savings);
    }

    /** "2026-10" reads "Oct 2026", "2026-Q4" reads "Oct–Dec 2026", "2026" stays. */
    static String words(String bucket) {
        String[] months = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
        if (bucket.matches("\\d{4}-\\d{2}")) return months[Integer.parseInt(bucket.substring(5)) - 1] + " " + bucket.substring(0, 4);
        if (bucket.matches("\\d{4}-Q[1-4]")) {
            int q = bucket.charAt(6) - '1';
            return months[q * 3] + "–" + months[q * 3 + 2] + " " + bucket.substring(0, 4);
        }
        return bucket;
    }

    private static void validate(LocalDate from, LocalDate to, String by) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ReportParameterException("from and to are required, and to cannot be before from");
        }
        if (!BY.contains(by)) throw new ReportParameterException("by must be one of " + BY + "; got '" + by + "'");
    }
}
