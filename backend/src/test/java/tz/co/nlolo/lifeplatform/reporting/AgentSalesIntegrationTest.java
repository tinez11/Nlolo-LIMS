package tz.co.nlolo.lifeplatform.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestEvents.Sale;
import tz.co.nlolo.lifeplatform.reporting.api.CompareWith;
import tz.co.nlolo.lifeplatform.reporting.api.PolicySold;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.application.AgentSales;
import tz.co.nlolo.lifeplatform.reporting.application.LabelsFactory;
import tz.co.nlolo.lifeplatform.reporting.application.ReportingClock;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An agent's sales in money (2026-11-12, analytics plan 3): the report agrees with the totals, "Me" is held to the
 * team, and the policies behind a figure are the ones counted. The team stands in for distribution; the sales are real
 * rows in the reports' copy.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(ReportingTestEvents.class)
class AgentSalesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            ReportingTestMigrations.ALL);
    }

    @Autowired private ReportingTestEvents events;
    @Autowired private ReportQueries queries;
    @Autowired private ReportingClock clock;

    private final DistributionApi distribution = mock(DistributionApi.class);
    private final UUID tenant = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();
    private final UUID me = UUID.randomUUID();
    private final UUID downline = UUID.randomUUID();
    private AgentSales sales;

    @BeforeEach
    void setUp() {
        TenantContext.set(tenant);
        sales = new AgentSales(queries, distribution,
            new LabelsFactory(mock(ProductApi.class), distribution, mock(PartyApi.class)), clock);
        when(distribution.resolveAgentTeam(party)).thenReturn(List.of(me, downline));
        events.sell(tenant, Sale.of("A-TERM", product, d("2026-10-05")).agent(me).premium("MONTHLY", "100000.00"));
        events.sell(tenant, Sale.of("A-CRL", product, d("2026-10-06")).agent(downline).premium("SINGLE", "500000.00"));
        events.sell(tenant, Sale.of("A-SEP", product, d("2026-09-10")).agent(me).premium("MONTHLY", "50000.00"));
        events.sell(tenant, Sale.of("A-NOT-MINE", product, d("2026-10-07")).agent(UUID.randomUUID()));
    }

    @AfterEach
    void leave() {
        TenantContext.clear();
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void theReportAgreesWithTheTotalsAndComparesWithTheMonthBefore() {
        ReportResult october = sales.report(party, null, d("2026-10-01"), d("2026-10-31"), "MONTH", CompareWith.PRIOR_PERIOD);

        assertThat(october.figures()).extracting(ReportFigure::key, ReportFigure::value, ReportFigure::prior).contains(
            org.assertj.core.groups.Tuple.tuple("premiumAYear", "1200000.00", "600000.00"),
            org.assertj.core.groups.Tuple.tuple("singlePremiums", "500000.00", "0.00"));
        assertThat(october.sections().get(0).rows()).singleElement()
            .satisfies(r -> {
                assertThat(r.get("period")).isEqualTo("Oct 2026");
                assertThat(r.get("policies")).isEqualTo("2");
            });
        assertThat(october.chart().series().get(0).points()).extracting(p -> p.period() + " " + p.value())
            .containsExactly("Oct 2026 1200000.00");
    }

    @Test
    void meIsHeldToTheTeam() {
        assertThat(sales.byPeriod(party, me, d("2026-10-01"), d("2026-10-31"), "MONTH")).singleElement()
            .satisfies(s -> assertThat(s.policies()).isEqualTo(1));
        assertThatThrownBy(() -> sales.byPeriod(party, UUID.randomUUID(), d("2026-10-01"), d("2026-10-31"), "MONTH"))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void thePoliciesBehindAFigureAreTheOnesCounted() {
        List<PolicySold> sold = sales.policies(party, null, d("2026-10-01"), d("2026-10-31"));

        // Newest first; another agent's policy is not in the team's list.
        assertThat(sold).extracting(PolicySold::policyNumber).containsExactly("A-CRL", "A-TERM");
        assertThat(sold).filteredOn(p -> p.policyNumber().equals("A-TERM")).singleElement()
            .satisfies(p -> assertThat(p.premiumAYear()).isEqualByComparingTo("1200000.00"));
        assertThat(sold).filteredOn(p -> p.policyNumber().equals("A-CRL")).singleElement()
            .satisfies(p -> assertThat(p.premiumAYear()).isNull());
    }
}
