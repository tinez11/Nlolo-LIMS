package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.regreporting.domain.MetricName;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.MetricReaderRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests over hand-built movement rows -- no Spring, no container. The cumulative-sum
 * semantics are the correctness core of this whole module, so they are tested where the arithmetic
 * is visible rather than only through a database.
 */
class CumulativeMetricTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();

    private static PolicyMovement movement(String period, int issued, int lapsed, String sumIssued, String sumTerminated) {
        PolicyMovement m = new PolicyMovement(TENANT, period, PRODUCT, "TZS");
        for (int i = 0; i < issued; i++) m.applyIssued(new BigDecimal(sumIssued));
        for (int i = 0; i < lapsed; i++) m.applyLapsed(new BigDecimal(sumTerminated));
        return m;
    }

    /** THE test for decision 4. Three periods of movements; a STOCK metric for the EARLIEST must
     * report that period's figure, not the latest. A regression to a running counter passes every
     * other test in this suite and fails this one. */
    @Test
    void aStockMetricForAnEarlierPeriodIgnoresLaterMovements() {
        List<PolicyMovement> all = List.of(
            movement("2026-Q1", 10, 0, "100.00", "0.00"),
            movement("2026-Q2", 5, 2, "100.00", "100.00"),
            movement("2026-Q3", 0, 3, "0.00", "100.00"));

        assertThat(MetricReaderRegistry.cumulativePolicyCount(all, "2026-Q1")).isEqualTo(10L);
        assertThat(MetricReaderRegistry.cumulativePolicyCount(all, "2026-Q2")).isEqualTo(13L);
        assertThat(MetricReaderRegistry.cumulativePolicyCount(all, "2026-Q3")).isEqualTo(10L);
    }

    /** A period with more terminations than issuances is a real business outcome, and the
     * cumulative figure must be allowed to fall. This is why return_line.numeric_value carries no
     * non-negativity CHECK even though every fact-table measure does. */
    @Test
    void aCumulativeFigureCanFallAndEvenGoNegative() {
        List<PolicyMovement> all = List.of(
            movement("2026-Q1", 2, 0, "100.00", "0.00"),
            movement("2026-Q2", 0, 5, "0.00", "100.00"));
        assertThat(MetricReaderRegistry.cumulativePolicyCount(all, "2026-Q2")).isEqualTo(-3L);
    }

    /** A FLOW metric is that period's own row and nothing else -- no accumulation. */
    @Test
    void aFlowMetricUsesOnlyItsOwnPeriod() {
        List<PolicyMovement> q2 = List.of(movement("2026-Q2", 5, 2, "100.00", "100.00"));
        assertThat(MetricReaderRegistry.sumIssued(q2)).isEqualTo(5L);
    }

    /** Sum assured accumulates as issued minus terminated, the money analogue of the count. */
    @Test
    void cumulativeSumAssuredNetsIssuedAgainstTerminated() {
        List<PolicyMovement> all = List.of(
            movement("2026-Q1", 2, 0, "1000.00", "0.00"),
            movement("2026-Q2", 1, 1, "1000.00", "1000.00"));
        assertThat(MetricReaderRegistry.cumulativeSumAssured(all, "2026-Q2"))
            .isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    /** Lexical comparison is what makes cumulative sums work over VARCHAR periods, and it is only
     * sound WITHIN one period kind -- which is why return_definition pins period_kind per return
     * type. Asserted so the assumption is visible rather than implicit. */
    @Test
    void quarterlyPeriodsSortLexically() {
        assertThat("2026-Q1".compareTo("2026-Q2")).isNegative();
        // no Q10 exists; if it did, lexical comparison would (wrongly) sort Q3 after it -- this
        // positive result is the documented limit, not a mistake.
        assertThat("2026-Q3".compareTo("2026-Q10")).isPositive();
        assertThat("2025-Q4".compareTo("2026-Q1")).isNegative();
    }

    /** An empty projection is zero, not an exception -- a tenant with no activity in a period
     * legitimately reports zero rather than failing to generate a return at all. */
    @Test
    void anEmptyProjectionReadsAsZero() {
        assertThat(MetricReaderRegistry.cumulativePolicyCount(List.of(), "2026-Q1")).isEqualTo(0L);
        assertThat(MetricReaderRegistry.sumIssued(List.of())).isEqualTo(0L);
    }
}
