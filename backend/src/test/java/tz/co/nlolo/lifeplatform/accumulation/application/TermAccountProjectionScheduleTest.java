package tz.co.nlolo.lifeplatform.accumulation.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.ValueBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quotation's growth chart reads the projection's schedule (2026-10-23). It must be the SAME calculation as the
 * totals, not a second one beside them: the last point is the maturity figures exactly, and every point grows.
 */
class TermAccountProjectionScheduleTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 1);

    private static AccumulationPlan plan() {
        return new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ZERO, BigDecimal.ZERO,
            List.of(new AccumulationChargeRow(1, null, new BigDecimal("1"), BigDecimal.ZERO, new BigDecimal("1000"))),
            new BigDecimal("3"), new BigDecimal("12"));
    }

    @Test
    void aLongTermIsScheduledYearByYearAndEndsOnTheMaturityFigures() {
        TermAccountProjection.Projection p = TermAccountProjection.project(new BigDecimal("100000"), "MONTHLY", 60,
            new BigDecimal("8"), START, plan());

        assertThat(p.schedule()).extracting(TermAccountProjection.Point::month).containsExactly(12, 24, 36, 48, 60);
        TermAccountProjection.Point last = p.schedule().get(p.schedule().size() - 1);
        assertThat(last.date()).isEqualTo(p.maturityDate());
        assertThat(last.value()).isEqualByComparingTo(p.maturityValue());
        assertThat(last.paidIn()).isEqualByComparingTo(p.paidIn());
        assertThat(last.interest()).isEqualByComparingTo(p.interest());
        assertThat(last.charges()).isEqualByComparingTo(p.charges());
        // Twelve monthly contributions are in by the first year end; the thirteenth lands on that day, after it.
        assertThat(p.schedule().get(0).paidIn()).isEqualByComparingTo("1200000");
        for (int i = 1; i < p.schedule().size(); i++) {
            assertThat(p.schedule().get(i).value()).isGreaterThan(p.schedule().get(i - 1).value());
        }
    }

    @Test
    void aShortTermIsScheduledMonthByMonth() {
        TermAccountProjection.Projection p = TermAccountProjection.project(new BigDecimal("50000"), "MONTHLY", 18,
            new BigDecimal("6"), START, plan());

        assertThat(p.schedule()).hasSize(18);
        assertThat(p.schedule().get(0).month()).isEqualTo(1);
        assertThat(p.schedule().get(17).value()).isEqualByComparingTo(p.maturityValue());
    }
}
