package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingValidationException;
import tz.co.nlolo.lifeplatform.regreporting.domain.MetricName;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ClaimsMovementRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.MetricReaderRegistry;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyMovementRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PremiumMovementRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ReinsuranceMovementRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests over hand-built movement rows -- no Spring, no container. The cumulative-sum
 * semantics are the correctness core of this whole module, so they are tested where the arithmetic
 * is visible rather than only through a database.
 *
 * <p>The final section adds {@code dimensionFilter} validation (M10 final review, I2) over the
 * INSTANCE {@code read(...)} with mocked repositories -- still no Spring and no container, because
 * every case there must be rejected before a single row is read.
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

    // ============================================================================================
    // dimensionFilter validation -- M10 final review, I2.
    //
    // These use the INSTANCE read(...) rather than the static arithmetic helpers above, so they need
    // the four repositories; they are mocked rather than containerised because none of these cases
    // reads a row at all -- every one of them must be rejected BEFORE any query runs. That is the
    // point: a filter the metric cannot honour must fail, never silently return the unfiltered
    // total (reinsurance) or silently match nothing (an unknown claim type). Both used to.
    // ============================================================================================

    private static MetricReaderRegistry registryOverEmptyTables() {
        PolicyMovementRepository policyMovements = mock(PolicyMovementRepository.class);
        ClaimsMovementRepository claimsMovements = mock(ClaimsMovementRepository.class);
        PremiumMovementRepository premiumMovements = mock(PremiumMovementRepository.class);
        ReinsuranceMovementRepository reinsuranceMovements = mock(ReinsuranceMovementRepository.class);
        when(claimsMovements.findByTenantIdAndPeriod(any(), any())).thenReturn(List.of());
        when(reinsuranceMovements.findByTenantIdAndPeriod(any(), any())).thenReturn(Optional.empty());
        return new MetricReaderRegistry(policyMovements, claimsMovements, premiumMovements, reinsuranceMovements);
    }

    /** A dimensionFilter on either reinsurance metric is unsatisfiable -- reinsurance_movement is
     * one row per (tenant, period) with no product grain at all -- so it must throw rather than
     * quietly hand back the tenant-wide total under a line label claiming it was filtered. An
     * OVERSTATED figure presented as filtered is the failure this closes. */
    @Test
    void aDimensionFilterOnAReinsuranceMetricIsRejectedRatherThanIgnored() {
        MetricReaderRegistry registry = registryOverEmptyTables();
        String someProductId = UUID.randomUUID().toString();

        for (MetricName metric : List.of(MetricName.REINSURANCE_CEDED_RISK, MetricName.REINSURANCE_CEDED_PREMIUM)) {
            RegreportingValidationException thrown = assertThrows(RegreportingValidationException.class,
                () -> registry.read(TENANT, metric, "2026-Q1", someProductId),
                "a dimensionFilter on " + metric + " must be rejected, not ignored");
            assertThat(thrown.getMessage()).contains("no product dimension");
        }

        // The unfiltered read is unaffected -- the rejection is about the filter, not the metric.
        assertThat(registry.read(TENANT, MetricName.REINSURANCE_CEDED_PREMIUM, "2026-Q1", null))
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** An unrecognised claim type must throw, exactly as a malformed product id already did --
     * matching zero rows and reporting 0 is indistinguishable on a return from "no claims of this
     * type occurred". The accepted vocabulary is claim_dimension.claim_type's CHECK
     * (db-migrations/regreporting/V2 section 5) plus the UNKNOWN sentinel. */
    @Test
    void anUnknownClaimTypeDimensionFilterIsRejectedRatherThanSilentlyMatchingNothing() {
        MetricReaderRegistry registry = registryOverEmptyTables();

        RegreportingValidationException thrown = assertThrows(RegreportingValidationException.class,
            () -> registry.read(TENANT, MetricName.CLAIMS_SETTLED_AMOUNT, "2026-Q1", "DEATHH"));
        assertThat(thrown.getMessage()).contains("not a known claim type");

        // A lower-case near-miss is just as wrong, and just as silent before this fix.
        assertThrows(RegreportingValidationException.class,
            () -> registry.read(TENANT, MetricName.CLAIMS_REGISTERED, "2026-Q1", "death"));

        // Every legitimate value still reads (zero here -- the point is that it does not throw).
        for (String claimType : List.of("DEATH", "DISABILITY", "CRITICAL_ILLNESS", "MATURITY", "UNKNOWN")) {
            assertThat(registry.read(TENANT, MetricName.CLAIMS_SETTLED, "2026-Q1", claimType))
                .as("%s is a legitimate claim-type filter", claimType)
                .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
