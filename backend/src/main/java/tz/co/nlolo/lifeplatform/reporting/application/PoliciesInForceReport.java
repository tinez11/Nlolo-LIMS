package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.InForceRow;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.StatusMove;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Policies on cover on a date, and (2026-11-09, the reports redesign batch 4) how the book moved since the date it is
 * compared with: opening + new + reinstated − lapsed − surrendered − matured − ended by a claim − cancelled in free-look
 * − ended = closing. Every policy that moved is counted once, by where it ended up, so the movement reconciles with the
 * in-force count on both dates by construction -- and says so if it ever does not.
 *
 * <p>The management overview (dashboard ManagementDashboard) reads the figures "policies" and "lapsed" by key: rename
 * them knowingly.
 */
@Component
public class PoliciesInForceReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("policies-in-force", "Policies in force",
        "Policies on cover on a date: count, sum assured and premium, and how the book moved since a month (or a year)"
            + " earlier. Suspended and paid-up policies are in force; a paid-up policy contributes no premium.",
        ReportCategory.MANAGEMENT, DateFilterKind.AS_OF, List.of(GroupBy.values()), true).comparingPeriods().from("REPORTING_COPY");

    static final Set<String> IN_FORCE = Set.of("ACTIVE", "SUSPENDED", "PAID_UP");

    /** The movement's lines in the order a reader adds them up: what came in, then what went out. */
    enum Move {
        NEW("New policies", true), REINSTATED("Reinstated", true), LAPSED("Lapsed", false),
        SURRENDERED("Surrendered", false), MATURED("Matured or expired", false), CLAIM("Ended by a claim", false),
        FREE_LOOK("Cancelled in free-look", false), ENDED("Ended (annuities)", false), OTHER("Other exits", false);

        final String label;
        final boolean in;

        Move(String label, boolean in) {
            this.label = label;
            this.in = in;
        }
    }

    private final ReportQueries queries;

    public PoliciesInForceReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    /** The date compared with: one asked for explicitly, else a month or a year earlier, else none. */
    static LocalDate openingOf(ReportFilter f) {
        return f.compareAsOf() != null ? f.compareAsOf() : f.comparisonDate();
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        return body(tenantId, f, labels).sections();
    }

    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        LocalDate opening = openingOf(f);
        List<InForceRow> nowRows = queries.inForce(tenantId, f.asOf(), groupBy, f);
        List<InForceRow> thenRows = opening == null ? List.of() : queries.inForce(tenantId, opening, groupBy, f);
        Map<String, InForceRow> now = index(nowRows);
        Map<String, InForceRow> then = index(thenRows);

        List<ReportColumn> columns = new ArrayList<>(List.of(col("dimension", groupBy.label(), TEXT),
            col("currency", "Currency", TEXT), col("policies", "Policies", COUNT), col("sumAssured", "Sum assured", MONEY),
            col("annualisedPremium", "Annualised premium", MONEY), col("singlePremiums", "Single premiums", MONEY)));
        if (opening != null) {
            columns.add(col("comparePolicies", "Policies on " + opening, COUNT));
            columns.add(col("policyChange", "Change", COUNT));
        }

        Set<String> keys = new LinkedHashSet<>(now.keySet());
        keys.addAll(then.keySet());
        List<Map<String, String>> rows = new ArrayList<>();
        for (String key : keys) {
            InForceRow n = now.get(key);
            InForceRow c = then.get(key);
            InForceRow any = n != null ? n : c;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("dimension", labels.dimension(groupBy, any.dimension()));
            row.put("currency", any.currency());
            row.put("policies", Figures.count(n == null ? 0 : n.policies()));
            row.put("sumAssured", Figures.money(n == null ? null : n.sumAssured()));
            row.put("annualisedPremium", Figures.money(n == null ? null : n.annualised()));
            row.put("singlePremiums", Figures.money(n == null ? null : n.single()));
            if (opening != null) {
                long before = c == null ? 0 : c.policies();
                row.put("comparePolicies", Figures.count(before));
                row.put("policyChange", Figures.count((n == null ? 0 : n.policies()) - before));
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("dimension")).thenComparing(r -> r.get("currency")));

        List<ReportSection> sections = new ArrayList<>();
        sections.add(new ReportSection("Policies in force on " + f.asOf(), columns, rows, Sections.totals(columns, rows)));

        long closing = nowRows.stream().mapToLong(InForceRow::policies).sum();
        List<ReportFigure> figures = new ArrayList<>();
        ReportChart chart = null;
        String comparison = null;
        if (opening == null) {
            figures.add(ClaimsReporting.count("policies", "Policies in force", closing, null, "UP", true));
        } else {
            long openCount = thenRows.stream().mapToLong(InForceRow::policies).sum();
            Map<Move, Long> moves = moves(queries.statusMoves(tenantId, opening, f.asOf(), groupBy, f), opening);
            sections.add(movementSection(opening, f.asOf(), openCount, moves, closing));
            chart = movementChart(openCount, moves, closing);
            comparison = "compared with " + opening;

            figures.add(ClaimsReporting.count("policies", "Policies in force", closing, openCount, "UP", true));
            figures.add(ClaimsReporting.count("new", "New policies", moves.get(Move.NEW), null, "UP", false));
            figures.add(ClaimsReporting.count("reinstated", "Reinstated", moves.get(Move.REINSTATED), null, "UP", false));
            figures.add(ClaimsReporting.count("lapsed", "Lapsed", moves.get(Move.LAPSED), null, "DOWN", false));
            figures.add(ClaimsReporting.count("surrendered", "Surrendered", moves.get(Move.SURRENDERED), null, "DOWN", false));
        }
        // Money is a headline only in one currency: shillings and dollars do not add up.
        if (nowRows.stream().map(InForceRow::currency).distinct().count() == 1) {
            BigDecimal premiumNow = nowRows.stream().map(InForceRow::annualised).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal coverNow = nowRows.stream().map(InForceRow::sumAssured).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean sameCurrency = opening != null && thenRows.stream().map(InForceRow::currency).distinct().count() <= 1;
            BigDecimal premiumThen = sameCurrency
                ? thenRows.stream().map(InForceRow::annualised).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
            BigDecimal coverThen = sameCurrency
                ? thenRows.stream().map(InForceRow::sumAssured).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
            figures.add(1, ClaimsReporting.money("annualisedPremium", "Annualised premium", premiumNow, premiumThen, "UP", true));
            figures.add(2, ClaimsReporting.money("sumAssured", "Sum assured", coverNow, coverThen, "UP", true));
        }
        return new ReportBody(sections, figures, chart, comparison);
    }

    /** Each moved policy, counted once by where it ended up; a policy in force on both dates did not move the book. */
    static Map<Move, Long> moves(List<StatusMove> moved, LocalDate opening) {
        Map<Move, Long> out = new LinkedHashMap<>();
        for (Move m : Move.values()) out.put(m, 0L);
        for (StatusMove m : moved) {
            Move move = move(m, opening);
            if (move != null) out.merge(move, 1L, Long::sum);
        }
        return out;
    }

    static Move move(StatusMove m, LocalDate opening) {
        boolean was = m.before() != null && IN_FORCE.contains(m.before());
        boolean is = m.after() != null && IN_FORCE.contains(m.after());
        if (was == is) return null;
        if (is) {
            boolean began = m.before() == null || (m.activatedOn() != null && m.activatedOn().isAfter(opening));
            return began ? Move.NEW : Move.REINSTATED;
        }
        return switch (m.after() == null ? "" : m.after()) {
            case "LAPSED" -> Move.LAPSED;
            case "SURRENDERED" -> Move.SURRENDERED;
            case "MATURED", "EXPIRED" -> Move.MATURED;
            case "CLAIM_TERMINATED" -> Move.CLAIM;
            case "CANCELLED_FREE_LOOK" -> Move.FREE_LOOK;
            case "ENDED" -> Move.ENDED;
            default -> Move.OTHER;
        };
    }

    private static ReportSection movementSection(LocalDate opening, LocalDate closingOn, long open, Map<Move, Long> moves,
                                                 long closing) {
        List<ReportColumn> columns = List.of(col("line", "Movement", TEXT), col("policies", "Policies", COUNT));
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(line("In force on " + opening, open, "SUBTOTAL"));
        long running = open;
        for (Move m : Move.values()) {
            long n = moves.get(m);
            if (n == 0 && m != Move.NEW && m != Move.LAPSED) continue;
            rows.add(line(m.label, m.in ? n : -n, "LINE"));
            running += m.in ? n : -n;
        }
        // Never expected: the moves are read the way in force is. If they ever disagree, the report says so.
        if (running != closing) rows.add(line("Not explained by the moves above", closing - running, "LINE"));
        rows.add(line("In force on " + closingOn, closing, "TOTAL"));
        return new ReportSection("How the book moved, " + opening + " to " + closingOn, columns, rows, Map.of());
    }

    private static Map<String, String> line(String label, long policies, String kind) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("line", label);
        row.put("policies", Figures.count(policies));
        row.put("_kind", kind);
        return row;
    }

    private static ReportChart movementChart(long open, Map<Move, Long> moves, long closing) {
        List<ReportChart.Bar> bars = new ArrayList<>();
        bars.add(new ReportChart.Bar("Opening", Long.toString(open), "START"));
        for (Move m : Move.values()) {
            long n = moves.get(m);
            if (n == 0 && m != Move.NEW && m != Move.LAPSED) continue;
            bars.add(new ReportChart.Bar(m.label, Long.toString(n), m.in ? "UP" : "DOWN"));
        }
        bars.add(new ReportChart.Bar("Closing", Long.toString(closing), "END"));
        return new ReportChart("BRIDGE", "How the book moved: policies in, policies out", bars, List.of(), "COUNT");
    }

    private static Map<String, InForceRow> index(List<InForceRow> rows) {
        Map<String, InForceRow> byKey = new LinkedHashMap<>();
        rows.forEach(r -> byKey.put(r.dimension() + "|" + r.currency(), r));
        return byKey;
    }
}
