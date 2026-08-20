package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingValidationException;
import tz.co.nlolo.lifeplatform.regreporting.application.ReturnGenerator;
import tz.co.nlolo.lifeplatform.regreporting.domain.MetricName;
import tz.co.nlolo.lifeplatform.regreporting.domain.RegulatoryReturn;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnDefinition;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnDefinitionLine;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnLine;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.MetricReaderRegistry;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.RegulatoryReturnRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ReturnDefinitionLineRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ReturnDefinitionRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ReturnLineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests over a {@link ReturnGenerator} built from hand-mocked repositories and a
 * mocked {@link MetricReaderRegistry} -- no Spring, no database. What must go through a real
 * Postgres instead (regeneration replacing rather than duplicating rows, cross-tenant
 * invisibility, real RLS) lives in {@code RegreportingApiIntegrationTest}.
 */
class ReturnGeneratorTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String RETURN_TYPE = "TEST_RETURN";

    private final ReturnDefinitionRepository definitionRepository = mock(ReturnDefinitionRepository.class);
    private final ReturnDefinitionLineRepository definitionLineRepository = mock(ReturnDefinitionLineRepository.class);
    private final RegulatoryReturnRepository returnRepository = mock(RegulatoryReturnRepository.class);
    private final ReturnLineRepository lineRepository = mock(ReturnLineRepository.class);
    private final MetricReaderRegistry metricReaderRegistry = mock(MetricReaderRegistry.class);

    private final ReturnGenerator generator = new ReturnGenerator(
        definitionRepository, definitionLineRepository, returnRepository, lineRepository, metricReaderRegistry);

    @BeforeEach
    void stubTheHappyPathReturnUpsert() {
        // No prior return for this triple, and save() just echoes back whatever it was given --
        // enough for tests that only care about ReturnLine content, not the header itself.
        when(returnRepository.findByTenantIdAndReturnTypeAndPeriod(eq(TENANT), eq(RETURN_TYPE), any()))
            .thenReturn(Optional.empty());
        when(returnRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static ReturnDefinition quarterlyDefinition() {
        return new ReturnDefinition(TENANT, RETURN_TYPE, "Test Return", null, "QUARTERLY");
    }

    private static ReturnDefinition annualDefinition() {
        return new ReturnDefinition(TENANT, RETURN_TYPE, "Test Annual Return", null, "ANNUAL");
    }

    @Test
    void linesAreResolvedInLineNoOrderWithCodeLabelMetricCopiedAndCurrencyOnlyOnMoney() {
        when(definitionRepository.findByTenantIdAndReturnType(TENANT, RETURN_TYPE))
            .thenReturn(Optional.of(quarterlyDefinition()));

        // Deliberately fed to the mock already in ascending line_no order, matching what
        // findByTenantIdAndReturnTypeOrderByLineNoAsc's real query guarantees -- this test proves
        // the generator does not scramble that order while mapping fields onto ReturnLine.
        ReturnDefinitionLine countLine = new ReturnDefinitionLine(TENANT, RETURN_TYPE, 1, "PL-01",
            "Policies issued in period", "POLICIES_ISSUED", null);
        ReturnDefinitionLine moneyLine = new ReturnDefinitionLine(TENANT, RETURN_TYPE, 2, "PL-02",
            "Sum assured in force", "SUM_ASSURED_IN_FORCE", null);
        when(definitionLineRepository.findByTenantIdAndReturnTypeOrderByLineNoAsc(TENANT, RETURN_TYPE))
            .thenReturn(List.of(countLine, moneyLine));

        when(metricReaderRegistry.read(TENANT, MetricName.POLICIES_ISSUED, "2026-Q3", null))
            .thenReturn(BigDecimal.valueOf(5));
        when(metricReaderRegistry.read(TENANT, MetricName.SUM_ASSURED_IN_FORCE, "2026-Q3", null))
            .thenReturn(new BigDecimal("1000000.00"));

        generator.generate(TENANT, RETURN_TYPE, "2026-Q3", "tester");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReturnLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(lineRepository).saveAll(captor.capture());
        List<ReturnLine> saved = captor.getValue();

        assertThat(saved).hasSize(2);

        ReturnLine first = saved.get(0);
        assertThat(first.getLineNo()).isEqualTo(1);
        assertThat(first.getLineCode()).isEqualTo("PL-01");
        assertThat(first.getLabel()).isEqualTo("Policies issued in period");
        assertThat(first.getMetricName()).isEqualTo(MetricName.POLICIES_ISSUED);
        assertThat(first.getNumericValue()).isEqualByComparingTo("5");
        assertThat(first.getCurrency()).isNull();

        ReturnLine second = saved.get(1);
        assertThat(second.getLineNo()).isEqualTo(2);
        assertThat(second.getLineCode()).isEqualTo("PL-02");
        assertThat(second.getLabel()).isEqualTo("Sum assured in force");
        assertThat(second.getMetricName()).isEqualTo(MetricName.SUM_ASSURED_IN_FORCE);
        assertThat(second.getNumericValue()).isEqualByComparingTo("1000000.00");
        assertThat(second.getCurrency()).isNotNull();
    }

    /** The whole point of catching {@code MetricName.valueOf}'s failure inside the generator: a
     * seeded (or onboarding-seeded) definition line naming a metric the registry does not know
     * must fail loudly and name the offender, never silently emit a line with a null value. */
    @Test
    void aDefinitionLineNamingAnUnknownMetricThrowsRegreportingValidationException() {
        when(definitionRepository.findByTenantIdAndReturnType(TENANT, RETURN_TYPE))
            .thenReturn(Optional.of(quarterlyDefinition()));
        ReturnDefinitionLine badLine = new ReturnDefinitionLine(TENANT, RETURN_TYPE, 1, "XX-01",
            "Not a real metric", "NOT_A_REAL_METRIC", null);
        when(definitionLineRepository.findByTenantIdAndReturnTypeOrderByLineNoAsc(TENANT, RETURN_TYPE))
            .thenReturn(List.of(badLine));

        RegreportingValidationException thrown = assertThrows(RegreportingValidationException.class,
            () -> generator.generate(TENANT, RETURN_TYPE, "2026-Q3", "tester"));
        assertThat(thrown.getMessage()).contains("NOT_A_REAL_METRIC");
    }

    /** Period-kind validation happens before anything else touches the metric registry or the
     * lines table -- proven here by never stubbing the definition-lines/metric mocks at all; if
     * the generator reached past validation, this test would fail with a Mockito
     * "unstubbed method" null rather than the expected exception. */
    @Test
    void anAnnualPeriodIsRejectedForAQuarterlyDefinitionBeforeAnyMetricIsRead() {
        when(definitionRepository.findByTenantIdAndReturnType(TENANT, RETURN_TYPE))
            .thenReturn(Optional.of(quarterlyDefinition()));

        assertThrows(RegreportingValidationException.class,
            () -> generator.generate(TENANT, RETURN_TYPE, "2026", "tester"));
    }

    /**
     * M10 final review, C2 -- a DIFFERENT code path from
     * {@link #anAnnualPeriodIsRejectedForAQuarterlyDefinitionBeforeAnyMetricIsRead} above, which
     * rejects a mismatched PERIOD against a QUARTERLY definition. This one exercises a definition
     * whose OWN {@code periodKind} is {@code ANNUAL} with a period that MATCHES it -- the
     * combination that used to be accepted as legitimate and then silently reported zeros for every
     * flow metric (no movement row is ever written with an annual-shaped period) and a year-stale
     * figure for every stock metric ({@code '2026-Q1' > '2026'} lexically, so {@code period <=
     * '2026'} excludes all of 2026). Annual generation must fail loudly until real annual
     * aggregation exists.
     *
     * <p>As with the sibling test above, the definition-lines and metric mocks are deliberately
     * never stubbed: if the generator reached past validation, this would fail on an unstubbed
     * Mockito null rather than with the expected exception.
     */
    @Test
    void anAnnualKindDefinitionIsRejectedOutrightEvenWithAMatchingAnnualPeriod() {
        when(definitionRepository.findByTenantIdAndReturnType(TENANT, RETURN_TYPE))
            .thenReturn(Optional.of(annualDefinition()));

        RegreportingValidationException thrown = assertThrows(RegreportingValidationException.class,
            () -> generator.generate(TENANT, RETURN_TYPE, "2026", "tester"));
        assertThat(thrown.getMessage())
            .as("the message must say annual returns are unsupported, not merely that the period "
                + "mismatched -- the two are different failures and a caller has to be able to tell "
                + "them apart")
            .contains("annual returns are not supported yet");
    }

    @Test
    void anUnknownReturnTypeThrows() {
        when(definitionRepository.findByTenantIdAndReturnType(TENANT, RETURN_TYPE))
            .thenReturn(Optional.empty());

        assertThrows(RegreportingValidationException.class,
            () -> generator.generate(TENANT, RETURN_TYPE, "2026-Q3", "tester"));
    }
}
