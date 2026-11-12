package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.ClaimFact;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;

/**
 * Where the open claims stand on a day (2026-11-12, analytics plan 5, "claims by stage"): each open claim counted once,
 * in the furthest stage it has reached, with how long it has waited there -- from the day it entered that stage.
 *
 * <p>The stages are the ones the reporting copy can replay from the audit journal: registered, first assessed
 * ({@code claims.ClaimAssessed}), approved, payment asked for. "Waiting for documents" is not here: a document request
 * sends no event, so its history cannot be rebuilt; the claims dashboard shows those live (D3). A claim reopened after
 * it was paid or declined counts as "Reopened" from the day it was reopened -- only its first assessment is kept, so
 * whether it has been looked at again is not known here.
 *
 * <p>The management overview (dashboard ManagementDashboard) reads the figure "open" by key: rename it knowingly.
 */
@Component
public class ClaimsInProgressReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("claims-in-progress", "Claims in progress",
        "Open claims on a date, by the stage each has reached, and how long they have waited there.",
        ReportCategory.CLAIMS, DateFilterKind.AS_OF, List.of(), false).from("REPORTING_COPY");

    static final String NOT_ASSESSED = "Waiting for assessment";
    static final String ASSESSED = "Assessed, waiting for a decision";
    static final String APPROVED = "Approved, payment not yet asked for";
    static final String PAYMENT_ASKED = "Payment asked for, not yet paid";
    static final String REOPENED = "Reopened";
    private static final List<String> ORDER = List.of(NOT_ASSESSED, ASSESSED, APPROVED, PAYMENT_ASKED, REOPENED);

    /** One stage: how many open claims are in it, their average wait and the longest. Waits are whole days. */
    record Stage(String name, long claims, BigDecimal averageDays, Long oldestDays) {}

    private final ReportQueries queries;

    public ClaimsInProgressReport(ReportQueries queries) {
        this.queries = queries;
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
        LocalDate on = f.asOf();
        List<Stage> stages = stages(queries.claimFacts(tenantId, on, f), on);

        List<ReportColumn> columns = List.of(new ReportColumn("stage", "Stage", TEXT),
            new ReportColumn("claims", "Claims", COUNT), new ReportColumn("averageDays", "Average days waiting", TEXT),
            new ReportColumn("oldestDays", "Longest wait (days)", COUNT));
        List<Map<String, String>> rows = new ArrayList<>();
        long open = 0;
        for (Stage s : stages) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("stage", s.name());
            r.put("claims", Figures.count(s.claims()));
            r.put("averageDays", s.averageDays() == null ? "" : s.averageDays().toPlainString());
            r.put("oldestDays", s.oldestDays() == null ? "" : Figures.count(s.oldestDays()));
            rows.add(r);
            open += s.claims();
        }
        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("stage", "Open claims");
        totals.put("claims", Figures.count(open));

        List<ReportFigure> figures = List.of(
            ClaimsReporting.count("open", "Open claims", open, null, "DOWN", true),
            ClaimsReporting.count("notAssessed", NOT_ASSESSED, claimsIn(stages, NOT_ASSESSED), null, "DOWN", true),
            ClaimsReporting.count("awaitingPayment", "Approved, not yet paid",
                claimsIn(stages, APPROVED) + claimsIn(stages, PAYMENT_ASKED), null, "DOWN", true));
        return new ReportBody(List.of(new ReportSection("Open claims by stage on " + on, columns, rows, totals)),
            figures, null, null);
    }

    /** Every stage in order, empty ones too: an empty stage is an answer ("nothing waits here"). */
    static List<Stage> stages(List<ClaimFact> claims, LocalDate on) {
        Map<String, List<Long>> waits = new LinkedHashMap<>();
        ORDER.forEach(s -> waits.put(s, new ArrayList<>()));
        for (ClaimFact c : claims) {
            if (!ClaimOutcomesReport.openOn(c, on)) continue;
            String stage;
            LocalDate since;
            if (reopened(c, on)) {
                stage = REOPENED;
                since = c.reopenedOn();
            } else if (by(c.settlementRequestedOn(), on)) {
                stage = PAYMENT_ASKED;
                since = c.settlementRequestedOn();
            } else if (by(c.approvedOn(), on)) {
                stage = APPROVED;
                since = c.approvedOn();
            } else if (by(c.assessedOn(), on)) {
                stage = ASSESSED;
                since = c.assessedOn();
            } else {
                stage = NOT_ASSESSED;
                since = c.registeredOn();
            }
            waits.get(stage).add(ChronoUnit.DAYS.between(since, on));
        }
        List<Stage> out = new ArrayList<>();
        waits.forEach((name, days) -> out.add(new Stage(name, days.size(),
            days.isEmpty() ? null : BigDecimal.valueOf(days.stream().mapToLong(Long::longValue).sum())
                .divide(BigDecimal.valueOf(days.size()), 1, RoundingMode.HALF_UP),
            days.stream().max(Long::compare).orElse(null))));
        return out;
    }

    /** Open on the day only because it was reopened after being paid or declined. */
    private static boolean reopened(ClaimFact c, LocalDate on) {
        return by(c.reopenedOn(), on) && (by(c.settledOn(), on) || by(c.rejectedOn(), on));
    }

    private static boolean by(LocalDate d, LocalDate on) {
        return d != null && !d.isAfter(on);
    }

    private static long claimsIn(List<Stage> stages, String name) {
        return stages.stream().filter(s -> s.name().equals(name)).mapToLong(Stage::claims).sum();
    }
}
