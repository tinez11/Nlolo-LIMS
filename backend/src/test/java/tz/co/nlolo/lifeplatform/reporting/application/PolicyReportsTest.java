package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reporting.api.CompareWith;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.StatusMove;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The policies and clients upgrades (2026-11-09, the reports redesign batch 4), on hand-made facts. */
class PolicyReportsTest {

    private static final LocalDate OPEN = LocalDate.of(2026, 9, 30);

    private static StatusMove moved(String activated, String before, String after) {
        return new StatusMove("P", "d", activated == null ? null : LocalDate.parse(activated), before, after);
    }

    @Test
    void eachMovedPolicyIsCountedOnceByWhereItEndedUp() {
        assertEquals(PoliciesInForceReport.Move.NEW, PoliciesInForceReport.move(moved("2026-10-05", null, "ACTIVE"), OPEN));
        assertEquals(PoliciesInForceReport.Move.REINSTATED,
            PoliciesInForceReport.move(moved("2025-01-01", "LAPSED", "ACTIVE"), OPEN));
        assertEquals(PoliciesInForceReport.Move.LAPSED,
            PoliciesInForceReport.move(moved("2025-01-01", "ACTIVE", "LAPSED"), OPEN));
        assertEquals(PoliciesInForceReport.Move.MATURED,
            PoliciesInForceReport.move(moved("2016-01-01", "PAID_UP", "EXPIRED"), OPEN));
        assertEquals(PoliciesInForceReport.Move.FREE_LOOK,
            PoliciesInForceReport.move(moved("2026-09-20", "ACTIVE", "CANCELLED_FREE_LOOK"), OPEN));
        // Suspended to active is in force on both dates: it did not move the book.
        assertNull(PoliciesInForceReport.move(moved("2025-01-01", "SUSPENDED", "ACTIVE"), OPEN));
        // Lapsed before and surrendered after: out on both dates.
        assertNull(PoliciesInForceReport.move(moved("2025-01-01", "LAPSED", "SURRENDERED"), OPEN));
    }

    /** Opening + what came in − what went out = closing, whatever mix of moves. */
    @Test
    void theMovementReconcilesOpeningToClosing() {
        List<StatusMove> moves = List.of(moved("2026-10-05", null, "ACTIVE"), moved("2026-10-07", null, "ACTIVE"),
            moved("2025-01-01", "LAPSED", "ACTIVE"), moved("2025-01-01", "ACTIVE", "LAPSED"),
            moved("2025-01-01", "ACTIVE", "SURRENDERED"), moved("2025-01-01", "SUSPENDED", "ACTIVE"));
        Map<PoliciesInForceReport.Move, Long> counted = PoliciesInForceReport.moves(moves, OPEN);
        long net = counted.entrySet().stream().mapToLong(e -> e.getKey().in ? e.getValue() : -e.getValue()).sum();
        assertEquals(2L, counted.get(PoliciesInForceReport.Move.NEW));
        assertEquals(1L, counted.get(PoliciesInForceReport.Move.REINSTATED));
        assertEquals(1L, net, "three in, two out");
    }

    @Test
    void aLapseFallsInThePolicyYearItHappenedIn() {
        LocalDate start = LocalDate.of(2024, 3, 15);
        assertEquals(1, LapseRateReport.policyYear(start, LocalDate.of(2025, 3, 14)));
        assertEquals(2, LapseRateReport.policyYear(start, LocalDate.of(2025, 3, 15)));
        assertEquals(3, LapseRateReport.policyYear(start, LocalDate.of(2026, 11, 1)));
        assertEquals(3, LapseRateReport.policyYear(LocalDate.of(2015, 1, 1), LocalDate.of(2026, 11, 1)), "3 or later");
    }

    @Test
    void aPeriodIsComparedWithTheSameLengthBeforeOrAYearEarlier() {
        ReportFilter october = ReportFilter.period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertArrayEquals(new LocalDate[] { LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30) },
            october.withCompareWith(CompareWith.PRIOR_PERIOD).comparisonSpan(), "whole months move by whole months");
        assertArrayEquals(new LocalDate[] { LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 31) },
            october.withCompareWith(CompareWith.SAME_PERIOD_LAST_YEAR).comparisonSpan());
        ReportFilter tenDays = ReportFilter.period(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 20));
        assertArrayEquals(new LocalDate[] { LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10) },
            tenDays.withCompareWith(CompareWith.PRIOR_PERIOD).comparisonSpan());
        assertNull(october.withCompareWith(CompareWith.NONE).comparisonSpan());
    }

    @Test
    void aComparedFigureReadsBothTotalRowsLikeForLike() {
        ReportFigure f = Sections.compared("policies", "Policies", Map.of("policies", "12"), Map.of("policies", "10"),
            "COUNT", "UP", true);
        assertEquals("2", f.change());
        assertEquals("20.0", f.changePercent());
        ReportFigure rate = Sections.compared("rate", "Rate", Map.of("rate", "12.50"), Map.of("rate", "10.00"),
            "PERCENT", "DOWN", true);
        assertEquals("2.50", rate.change());
        assertNull(rate.changePercent(), "a change in a rate is points, not per cent");
        assertNull(Sections.compared("amount", "Amount", Map.of("amount", ""), null, "MONEY", "UP", true));
    }
}
