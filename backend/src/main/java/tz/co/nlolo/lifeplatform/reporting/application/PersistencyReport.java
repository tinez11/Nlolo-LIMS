package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.AnnualisedPremium;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.domain.PersistencyMonths;
import tz.co.nlolo.lifeplatform.reporting.domain.PolicyStatus;
import tz.co.nlolo.lifeplatform.reporting.domain.StatusTimeline;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.CohortRow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
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
 * Of the policies activated in the cohort months, the share still in force at the start of month 13, 25, 37
 * and 61, by count and by annualised premium. Free-look cancellations, single premiums and investment
 * contracts are left out (SAMA's rule for the first; the others cannot lapse). Computed in Java over each
 * policy's StatusTimeline because the measurement date differs per policy.
 */
@Component
public class PersistencyReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("persistency", "Persistency",
        "Share of a cohort of new policies still in force at months 13, 25, 37 and 61, by count and by premium. "
            + "A month appears once every policy in the cohort has reached it.",
        ReportCategory.MANAGEMENT, DateFilterKind.COHORT, List.of(GroupBy.values()), false);

    private final ReportQueries queries;
    private final Clock clock;

    public PersistencyReport(ReportQueries queries, ReportingClock clock) {
        this.queries = queries;
        this.clock = clock.clock();
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    private record CohortPolicy(String dimension, LocalDate activatedOn, BigDecimal annualised,
                                List<StatusTimeline.Change> changes) {}

    private static final class Tally {
        long policies;
        BigDecimal premium = BigDecimal.ZERO;
        final Map<Integer, long[]> counts = new TreeMap<>();
        final Map<Integer, BigDecimal> premiums = new TreeMap<>();
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        LocalDate today = CivilDates.today(clock);
        List<Integer> months = PersistencyMonths.MONTHS.stream()
            .filter(m -> PersistencyMonths.reached(f.cohortTo(), m, today)).toList();

        Map<String, CohortPolicy> policies = new LinkedHashMap<>();
        for (CohortRow r : queries.cohort(tenantId, f.cohortFrom(), f.cohortTo(), groupBy, f)) {
            policies.computeIfAbsent(r.policyNumber(), k -> new CohortPolicy(r.dimension(), r.activatedOn(),
                    AnnualisedPremium.of(r.premium(), r.frequency()).orElse(BigDecimal.ZERO), new ArrayList<>()))
                .changes().add(new StatusTimeline.Change(r.effectiveOn(), r.occurredAt(), r.eventId(), r.status()));
        }

        Map<String, Tally> byDimension = new TreeMap<>();
        Tally all = new Tally();
        for (CohortPolicy p : policies.values()) {
            StatusTimeline timeline = new StatusTimeline(p.changes());
            if (timeline.ever(PolicyStatus.CANCELLED_FREE_LOOK)) continue;
            for (Tally t : List.of(byDimension.computeIfAbsent(p.dimension(), k -> new Tally()), all)) {
                t.policies++;
                t.premium = t.premium.add(p.annualised());
                for (int m : months) {
                    boolean persists = timeline.inForceOn(PersistencyMonths.measuredOn(p.activatedOn(), m));
                    t.counts.computeIfAbsent(m, k -> new long[1])[0] += persists ? 1 : 0;
                    t.premiums.merge(m, persists ? p.annualised() : BigDecimal.ZERO, BigDecimal::add);
                }
            }
        }

        List<ReportColumn> columns = new ArrayList<>(List.of(col("dimension", groupBy.label(), TEXT),
            col("policies", "Policies in cohort", COUNT), col("annualisedPremium", "Annualised premium", MONEY)));
        for (int m : months) {
            columns.add(col("month" + m + "ByCount", "Month " + m + " by count %", PERCENT));
            columns.add(col("month" + m + "ByPremium", "Month " + m + " by premium %", PERCENT));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        byDimension.forEach((dimension, tally) -> rows.add(row(labels.dimension(groupBy, dimension), tally, months)));
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("dimension")));
        String title = "Activated " + f.cohortFrom() + " to " + f.cohortTo()
            + (months.isEmpty() ? " -- no persistency month reached yet" : "");
        return List.of(new ReportSection(title, columns, rows, rows.isEmpty() ? Map.of() : row("Total", all, months)));
    }

    private static Map<String, String> row(String label, Tally t, List<Integer> months) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("dimension", label);
        row.put("policies", Figures.count(t.policies));
        row.put("annualisedPremium", Figures.money(t.premium));
        for (int m : months) {
            row.put("month" + m + "ByCount", Figures.percent(BigDecimal.valueOf(t.counts.get(m)[0]), BigDecimal.valueOf(t.policies)));
            row.put("month" + m + "ByPremium", Figures.percent(t.premiums.get(m), t.premium));
        }
        return row;
    }
}
