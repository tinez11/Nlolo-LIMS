package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionAccrualView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/** The reinsurance and commission reports (2026-11-09, the reports redesign batch 5), on hand-made rows. */
class ReinsuranceAndCommissionsTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static CommissionAccrualView accrual(TierType tier, String amount, UUID reverses) {
        return new CommissionAccrualView(UUID.randomUUID(), AGENT, UUID.randomUUID(), "P-1", tier, new BigDecimal(amount),
            "TZS", "2026-10", "ref", reverses, null, null, PRODUCT, "Term", LocalDate.of(2026, 10, 5));
    }

    private static CommissionStatementView statement(String period, String total, StatementStatus status) {
        return new CommissionStatementView(UUID.randomUUID(), AGENT, period, new BigDecimal(total), "TZS", status, null, null);
    }

    /**
     * 1,000 first-year + 200 renewal + 50 override, less a 300 clawback, is 950 booked; 600 of it paid on a statement
     * leaves 350 unpaid. Against 10,000 of premium, commission is 9.5% of it.
     */
    @Test
    void commissionRunsFromEarnedThroughClawbacksToPaid() {
        DistributionApi distribution = Mockito.mock(DistributionApi.class);
        Mockito.when(distribution.listAccrualsBetween("2026-10", "2026-10")).thenReturn(List.of(
            accrual(TierType.FIRST_YEAR, "1000.00", null), accrual(TierType.RENEWAL, "200.00", null),
            accrual(TierType.SUPERVISOR_OVERRIDE, "50.00", null),
            accrual(TierType.FIRST_YEAR, "-300.00", UUID.randomUUID())));
        Mockito.when(distribution.listStatementsBetween("2026-10", "2026-10")).thenReturn(List.of(
            statement("2026-10", "600.00", StatementStatus.PAID)));
        ReportQueries queries = Mockito.mock(ReportQueries.class);
        Mockito.when(queries.collectedByProduct(any(), any(), any(), any()))
            .thenReturn(Map.of(PRODUCT.toString(), new BigDecimal("10000.00")));
        ReportingClock clock = Mockito.mock(ReportingClock.class);
        Mockito.when(clock.clock()).thenReturn(Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneId.of("UTC")));

        ReportBody body = new CommissionsReport(distribution, queries, clock)
            .body(UUID.randomUUID(), ReportFilter.months(YearMonth.of(2026, 10), YearMonth.of(2026, 10)),
                Mockito.mock(Labels.class));

        ReportSection movement = body.sections().get(0);
        assertEquals(List.of("1000.00", "200.00", "50.00", "0.00", "-300.00", "950.00", "-600.00", "350.00"),
            movement.rows().stream().map(r -> r.get("amount")).toList());
        assertEquals("950.00", body.sections().get(2).rows().get(0).get("commission"));
        assertEquals("9.50", body.sections().get(2).rows().get(0).get("ratio"));
        assertEquals("9.5", body.figures().stream().filter(f -> f.key().equals("ratio")).findFirst()
            .map(ReportFigure::value).orElseThrow());
    }

    @Test
    void anUnpaidStatementIsAgedFromItsMonth() {
        ReportSection s = CommissionsReport.unpaid(List.of(statement("2026-10", "100.00", StatementStatus.CLOSED),
            statement("2026-07", "40.00", StatementStatus.AWAITING_PAYOUT_APPROVAL),
            statement("2026-01", "10.00", StatementStatus.PAYOUT_FAILED),
            statement("2026-09", "999.00", StatementStatus.PAID)), LocalDate.of(2026, 11, 9));
        assertEquals(List.of("100.00", "0.00", "40.00", "10.00"), s.rows().stream().map(r -> r.get("amount")).toList());
        assertEquals("150.00", s.totals().get("amount"), "a paid statement is not unpaid");
    }

    @Test
    void aStatementCountsWhenItsQuarterTouchesTheMonths() {
        assertTrue(ReinsuranceReport.overlaps("2026-Q3", YearMonth.of(2026, 9), YearMonth.of(2026, 10)));
        assertTrue(ReinsuranceReport.overlaps("2026-Q4", YearMonth.of(2026, 9), YearMonth.of(2026, 10)));
        assertFalse(ReinsuranceReport.overlaps("2026-Q2", YearMonth.of(2026, 9), YearMonth.of(2026, 10)));
        assertFalse(ReinsuranceReport.overlaps("garbage", YearMonth.of(2026, 1), YearMonth.of(2026, 12)));
        assertEquals("Pending approval", ReinsuranceReport.readable("PENDING_APPROVAL"));
    }
}
