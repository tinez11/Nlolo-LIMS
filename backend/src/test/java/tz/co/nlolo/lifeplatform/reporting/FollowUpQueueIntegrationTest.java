package tz.co.nlolo.lifeplatform.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceDueView;
import tz.co.nlolo.lifeplatform.billing.api.OpenPremiumView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestEvents.Sale;
import tz.co.nlolo.lifeplatform.reporting.application.FollowUpQueue;
import tz.co.nlolo.lifeplatform.reporting.application.LabelsFactory;
import tz.co.nlolo.lifeplatform.reporting.application.ReportingClock;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.api.CollectionFigures;
import tz.co.nlolo.lifeplatform.reporting.api.FollowUpPage;
import tz.co.nlolo.lifeplatform.reporting.api.FollowUpRow;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi.FollowUpQuery;
import tz.co.nlolo.lifeplatform.reporting.domain.CivilDates;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Premiums to follow up (2026-11-11, analytics plan 1): billing's premiums owed, joined to the reporting projection for
 * product, agent and branch. Billing, party and distribution are stood in for -- what is under test is the composing:
 * the tabs, the late bands, the agent's team, the offer left out, and the collection rate.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(ReportingTestEvents.class)
class FollowUpQueueIntegrationTest {

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
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private ReportingClock reportingClock;

    // Stood in for here, not in the context: other billing beans inject BillingApiImpl itself.
    private final BillingApi billing = mock(BillingApi.class);
    private final PartyApi parties = mock(PartyApi.class);
    private final DistributionApi distribution = mock(DistributionApi.class);
    private FollowUpQueue queue;

    private final UUID tenant = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final UUID myAgent = UUID.randomUUID();
    private final UUID otherAgent = UUID.randomUUID();
    private final UUID agentParty = UUID.randomUUID();
    private final UUID holder = UUID.randomUUID();
    private final LocalDate today = CivilDates.today(Clock.systemUTC());

    private final UUID soon = UUID.randomUUID();
    private final UUID dueToday = UUID.randomUUID();
    private final UUID late = UUID.randomUUID();
    private final UUID offer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(tenant);
        queue = new FollowUpQueue(billing, parties, distribution, queries,
            new LabelsFactory(mock(ProductApi.class), distribution, parties), publisher, reportingClock);
        LocalDate sold = today.minusMonths(3);
        events.sell(tenant, Sale.of("FU-SOON", product, sold).agent(myAgent).holder(holder));
        events.sell(tenant, Sale.of("FU-TODAY", product, sold).agent(otherAgent).holder(holder));
        events.sell(tenant, Sale.of("FU-LATE", product, sold).agent(myAgent).holder(holder));
        events.offerNotTakenUp(tenant, Sale.of("FU-OFFER", product, sold), today.minusDays(1));

        OpenPremiumView.ActionView called = new OpenPremiumView.ActionView("CALL", "WILL_PAY", today.plusDays(2),
            "Pays Friday", "Asha", Instant.now());
        when(billing.openPremiums(any())).thenReturn(List.of(
            open(late, "FU-LATE", today.minusDays(20), "15000.00", List.of(called)),
            open(dueToday, "FU-TODAY", today, "20000.00", List.of()),
            open(soon, "FU-SOON", today.plusDays(3), "10000.00", List.of()),
            open(offer, "FU-OFFER", today.minusDays(5), "9000.00", List.of())));
        when(parties.displayNames(anyCollection())).thenReturn(Map.of(holder, "Asha Juma"));
        when(parties.phoneNumbers(anyCollection())).thenReturn(Map.of(holder, "+255712000111"));
        when(distribution.resolveAgentTeam(agentParty)).thenReturn(List.of(myAgent));
    }

    @AfterEach
    void leaveTenant() {
        TenantContext.clear();
    }

    private static OpenPremiumView open(UUID id, String policy, LocalDate due, String owed,
                                        List<OpenPremiumView.ActionView> actions) {
        return new OpenPremiumView(id, policy, due, new BigDecimal(owed), "TZS", "OVERDUE", null, null, null, actions);
    }

    private static FollowUpQuery query(String view) {
        return new FollowUpQuery(view, 7, null, null, null, null, null, 0, 50);
    }

    @Test
    void eachTabHoldsItsOwnPremiumsAndTheOfferIsLeftOut() {
        FollowUpPage dueSoon = queue.page(query("DUE_SOON"), null);
        assertThat(dueSoon.rows()).extracting(FollowUpRow::invoiceId).containsExactly(soon);
        assertThat(dueSoon.dueToday()).isEqualTo(1);
        assertThat(dueSoon.dueSoon()).isEqualTo(1);
        assertThat(dueSoon.overdue()).isEqualTo(1);
        assertThat(dueSoon.overdueOwed()).isEqualByComparingTo("15000.00");

        assertThat(queue.page(query("DUE_TODAY"), null).rows()).extracting(FollowUpRow::invoiceId)
            .containsExactly(dueToday);

        FollowUpPage overdue = queue.page(query("OVERDUE"), null);
        assertThat(overdue.rows()).singleElement().satisfies(r -> {
            assertThat(r.invoiceId()).isEqualTo(late);
            assertThat(r.daysLate()).isEqualTo(20);
            assertThat(r.lateBand()).isEqualTo("15–30 days");
            assertThat(r.clientName()).isEqualTo("Asha Juma");
            assertThat(r.clientPhone()).isEqualTo("+255712000111");
            assertThat(r.agentId()).isEqualTo(myAgent);
            assertThat(r.policyStatus()).isEqualTo("Active");
            assertThat(r.actions()).hasSize(1);
        });
        assertThat(queue.page(new FollowUpQuery("OVERDUE", 7, "1–7 days", null, null, null, null, 0, 50), null)
            .rows()).isEmpty();
    }

    @Test
    void anAgentSeesOnlyTheirTeamsPremiums() {
        FollowUpPage mine = queue.page(query("DUE_TODAY"), agentParty);
        assertThat(mine.rows()).isEmpty();
        assertThat(mine.dueToday()).isZero();
        assertThat(queue.page(query("OVERDUE"), agentParty).rows())
            .extracting(FollowUpRow::policyNumber).containsExactly("FU-LATE");
        assertThat(queue.page(query("OVERDUE"), UUID.randomUUID()).rows()).isEmpty();
    }

    @Test
    void theDownloadPrintsWhatWasDoneInWords() {
        ReportResult result = queue.report(query("OVERDUE"), null);
        assertThat(result.sections().get(0).rows()).singleElement().satisfies(row -> {
            assertThat(row.get("policy")).isEqualTo("FU-LATE");
            assertThat(row.get("owed")).isEqualTo("15000.00");
            assertThat(row.get("daysLate")).isEqualTo("20");
            assertThat(row.get("done")).startsWith("Called ").contains("— promised ");
        });
        assertThat(result.sections().get(0).totals()).containsEntry("owed", "15000.00");
    }

    @Test
    void theCollectionRateIsWhatWasPaidOfWhatFellDue() {
        LocalDate from = today.withDayOfMonth(1);
        when(billing.invoicesDueBetween(from, today)).thenReturn(List.of(
            new InvoiceDueView(UUID.randomUUID(), "FU-SOON", from, new BigDecimal("10000.00"), BigDecimal.ZERO, "TZS", "PAID"),
            new InvoiceDueView(UUID.randomUUID(), "FU-LATE", from, new BigDecimal("6000.00"), new BigDecimal("4000.00"),
                "TZS", "PARTIALLY_PAID")));

        CollectionFigures figures = queue.figures(from, today, today);

        assertThat(figures.due()).isEqualByComparingTo("16000.00");
        assertThat(figures.paid()).isEqualByComparingTo("12000.00");
        assertThat(figures.ratePercent()).isEqualTo("75.0");
        assertThat(figures.dueTodayCount()).isEqualTo(1);
        assertThat(figures.dueTodayOwed()).isEqualByComparingTo("20000.00");
        // The list's own rows: the offer never taken up is not counted overdue here either.
        assertThat(figures.overdueCount()).isEqualTo(1);
        assertThat(figures.overdueOwed()).isEqualByComparingTo("15000.00");
    }
}
