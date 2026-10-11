package tz.co.nlolo.lifeplatform.reporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestEvents.Sale;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(ReportingTestEvents.class)
class ReportQueriesIntegrationTest {

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
    @Autowired private ReportingApi reportingApi;

    // A fresh tenant per test: JUnit creates a new instance for every test method.
    private final UUID tenant = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();

    @BeforeEach
    void enterTenant() {
        TenantContext.set(tenant);
    }

    @AfterEach
    void leaveTenant() {
        TenantContext.clear();
    }

    private ReportResult run(String key, ReportFilter filter) {
        return reportingApi.run(key, filter);
    }

    private static List<Map<String, String>> rows(ReportResult result, int section) {
        return result.sections().get(section).rows();
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    // ---- policies in force -------------------------------------------------------------------------------

    @Test
    void inForceCountsActiveSuspendedAndPaidUpButNotLapsed() {
        events.sell(tenant, Sale.of("P-A", product, d("2026-01-05")));
        events.sell(tenant, Sale.of("P-B", product, d("2026-01-06")).premium("QUARTERLY", "30000.00"));
        events.sell(tenant, Sale.of("P-C", product, d("2026-01-07")).premium("SINGLE", "500000.00"));
        events.sell(tenant, Sale.of("P-D", product, d("2026-01-08")));
        events.lapse(tenant, "P-D", d("2026-03-01"));
        events.sell(tenant, Sale.of("P-E", product, d("2026-01-09")));
        events.suspend(tenant, "P-E", d("2026-03-02"));
        events.sell(tenant, Sale.of("P-F", product, d("2026-01-10")));
        events.paidUp(tenant, "P-F", d("2026-03-03"));

        ReportResult result = run("policies-in-force", ReportFilter.asOf(d("2026-03-31")));

        assertThat(rows(result, 0)).singleElement().satisfies(row -> {
            assertThat(row.get("dimension")).isEqualTo(product.toString());
            assertThat(row.get("policies")).isEqualTo("5");
            // P-A 120000 + P-B 120000 + P-E 120000; P-F is paid up (0); P-C is single.
            assertThat(row.get("annualisedPremium")).isEqualTo("360000.00");
            assertThat(row.get("singlePremiums")).isEqualTo("500000.00");
            // P-F's sum assured is its paid-up 500000.
            assertThat(row.get("sumAssured")).isEqualTo("4500000.00");
        });
        assertThat(result.sections().get(0).totals().get("policies")).isEqualTo("5");
        assertThat(rows(run("policies-in-force", ReportFilter.asOf(d("2026-01-04"))), 0)).isEmpty();
    }

    @Test
    void inForceComparesTwoDates() {
        events.sell(tenant, Sale.of("P-A", product, d("2026-01-05")));
        events.sell(tenant, Sale.of("P-B", product, d("2026-02-05")));
        ReportResult result = run("policies-in-force",
            ReportFilter.asOf(d("2026-02-28")).withCompareAsOf(d("2026-01-31")));
        assertThat(rows(result, 0)).singleElement().satisfies(row -> {
            assertThat(row.get("policies")).isEqualTo("2");
            assertThat(row.get("comparePolicies")).isEqualTo("1");
            assertThat(row.get("policyChange")).isEqualTo("1");
        });
    }

    @Test
    void inForceGroupsByAgentAndNamesADirectSale() {
        events.sell(tenant, Sale.of("P-A", product, d("2026-01-05")));
        ReportResult result = run("policies-in-force", ReportFilter.asOf(d("2026-01-31")).withGroupBy(GroupBy.AGENT));
        assertThat(rows(result, 0)).extracting(r -> r.get("dimension")).containsExactly("Direct (no agent)");
        assertThat(result.sections().get(0).columns().get(0).label()).isEqualTo("Agent");
    }

    @Test
    void theClientTypeFilterNarrowsByTheHoldersType() {
        UUID person = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        events.client(tenant, person, "INDIVIDUAL", "VERIFIED", "Asha", d("2025-12-01"), d("1990-01-01"), "FEMALE");
        events.client(tenant, company, "CORPORATE", "VERIFIED", "Kilimo Ltd", d("2025-12-01"), null, null);
        events.sell(tenant, Sale.of("P-A", product, d("2026-01-05")).holder(person));
        events.sell(tenant, Sale.of("P-B", product, d("2026-01-05")).holder(company));
        ReportResult result = run("policies-in-force", ReportFilter.asOf(d("2026-01-31")).withClientType("CORPORATE"));
        assertThat(rows(result, 0)).singleElement().satisfies(row -> assertThat(row.get("policies")).isEqualTo("1"));
    }

    // ---- new business ------------------------------------------------------------------------------------

    @Test
    void newBusinessCountsActivationsAndOffersNotTakenUpByMonth() {
        events.sell(tenant, Sale.of("N-1", product, d("2026-01-10")));
        events.sell(tenant, Sale.of("N-2", product, d("2026-01-20")).premium("SINGLE", "200000.00"));
        events.sell(tenant, Sale.of("N-3", product, d("2026-02-03")));
        events.offerNotTakenUp(tenant, Sale.of("N-4", product, d("2026-01-15")), d("2026-02-14"));

        ReportResult result = run("new-business", ReportFilter.period(d("2026-01-01"), d("2026-02-28")));

        assertThat(rows(result, 0)).extracting(r -> r.get("month") + " " + r.get("policies") + " "
                + r.get("notTakenUp") + " " + r.get("takeUpRate") + " " + r.get("annualisedPremium") + " " + r.get("singlePremiums"))
            .containsExactly("2026-01 2 0 100.00 120000.00 200000.00", "2026-02 1 1 50.00 120000.00 0.00");
        assertThat(result.sections().get(0).totals().get("takeUpRate")).isEqualTo("75.00");
    }

    // ---- lapse rate --------------------------------------------------------------------------------------

    @Test
    void lapseRateIsLapsesOverAverageInForceGrossAndNetOfReinstatements() {
        for (String n : List.of("L-1", "L-2", "L-3", "L-4")) {
            events.sell(tenant, Sale.of(n, product, d("2025-01-10")));
        }
        events.lapse(tenant, "L-1", d("2025-03-05"));
        events.lapse(tenant, "L-2", d("2025-03-20"));
        events.reinstate(tenant, "L-2", d("2025-03-25"));

        ReportResult result = run("lapse-rate", ReportFilter.period(d("2025-03-01"), d("2025-03-31")));

        assertThat(rows(result, 0)).singleElement().satisfies(row -> {
            assertThat(row.get("inForceStart")).isEqualTo("4");
            assertThat(row.get("inForceEnd")).isEqualTo("3");
            assertThat(row.get("averageInForce")).isEqualTo("3.50");
            assertThat(row.get("lapses")).isEqualTo("2");
            assertThat(row.get("reinstatements")).isEqualTo("1");
            assertThat(row.get("grossLapseRate")).isEqualTo("57.14");
            assertThat(row.get("netLapseRate")).isEqualTo("28.57");
        });
    }

    // ---- persistency -------------------------------------------------------------------------------------

    @Test
    void persistencyFollowsACohortAndLeavesOutFreeLookSingleAndUnreachedMonths() {
        LocalDate jan = d("2023-01-15");
        events.sell(tenant, Sale.of("C-A", product, jan));                                       // stays in force
        events.sell(tenant, Sale.of("C-B", product, jan).premium("QUARTERLY", "30000.00"));      // lapses before month 13
        events.lapse(tenant, "C-B", d("2023-08-01"));
        events.sell(tenant, Sale.of("C-C", product, jan).premium("ANNUALLY", "60000.00"));       // lapses between 13 and 25
        events.lapse(tenant, "C-C", d("2024-06-01"));
        events.sell(tenant, Sale.of("C-D", product, jan));                                       // free-look: excluded
        events.cancelFreeLook(tenant, "C-D", d("2023-01-20"));
        events.sell(tenant, Sale.of("C-E", product, jan).premium("SINGLE", "500000.00"));        // single: excluded
        events.sell(tenant, Sale.of("C-F", product, jan).portfolio("SAV"));                      // investment: excluded

        ReportResult result = run("persistency", ReportFilter.cohort(YearMonth.of(2023, 1), YearMonth.of(2023, 1)));

        assertThat(result.sections().get(0).columns()).extracting(ReportColumn::key)
            .contains("month13ByCount", "month25ByCount", "month37ByCount").doesNotContain("month61ByCount");
        assertThat(rows(result, 0)).singleElement().satisfies(row -> {
            assertThat(row.get("policies")).isEqualTo("3");
            assertThat(row.get("annualisedPremium")).isEqualTo("300000.00");
            assertThat(row.get("month13ByCount")).isEqualTo("66.67");
            assertThat(row.get("month13ByPremium")).isEqualTo("60.00");
            assertThat(row.get("month25ByCount")).isEqualTo("33.33");
            assertThat(row.get("month25ByPremium")).isEqualTo("40.00");
        });
    }

    // ---- premium collections -----------------------------------------------------------------------------

    @Test
    void collectionsAreTotalledByMonthAndChannelWithTheTopPolicies() {
        events.sell(tenant, Sale.of("K-1", product, d("2026-01-02")));
        events.sell(tenant, Sale.of("K-2", product, d("2026-01-02")));
        events.collect(tenant, "K-1", "10000.00", d("2026-01-05"), true);
        events.collect(tenant, "K-1", "10000.00", d("2026-01-25"), true);
        events.collect(tenant, "K-2", "4000.00", d("2026-01-26"), false);
        events.collect(tenant, "K-2", "10000.00", d("2026-02-05"), true);

        ReportResult result = run("premium-collections", ReportFilter.period(d("2026-01-01"), d("2026-02-28")));

        assertThat(rows(result, 0)).extracting(r -> r.get("month") + " " + r.get("channel") + " " + r.get("receipts")
                + " " + r.get("amount"))
            .containsExactly("2026-01 FIELD_CASH 1 4000.00", "2026-01 MOBILE_MONEY 2 20000.00", "2026-02 MOBILE_MONEY 1 10000.00");
        assertThat(result.sections().get(0).totals().get("amount")).isEqualTo("34000.00");
        assertThat(rows(result, 1)).extracting(r -> r.get("policyNumber")).containsExactly("K-1", "K-2");
    }

    // ---- clients -----------------------------------------------------------------------------------------

    @Test
    void clientsAreCountedByTypeAgeBandAndSchemeMembership() {
        UUID asha = UUID.randomUUID();
        UUID juma = UUID.randomUUID();
        UUID coop = UUID.randomUUID();
        events.client(tenant, asha, "INDIVIDUAL", "VERIFIED", "Asha", d("2026-01-05"), d("1990-04-01"), "FEMALE");
        events.client(tenant, juma, "INDIVIDUAL", "PENDING", "Juma", d("2026-02-10"), d("2000-01-01"), "MALE");
        events.client(tenant, coop, "CORPORATE", "VERIFIED", "Upendo Cooperative", d("2025-12-01"), null, null);
        events.sell(tenant, Sale.of("I-1", product, d("2026-01-06")).holder(asha));
        events.sell(tenant, Sale.of("GRP-1", product, d("2026-01-02")).holder(coop).scheme(10));
        events.members(tenant, "GRP-1", "JOINED", 12, "12000000.00", d("2026-02-01"));
        events.members(tenant, "GRP-1", "EXITED", 11, "11000000.00", d("2026-03-01"));

        ReportResult result = run("clients", ReportFilter.asOf(d("2026-03-31")).withPeriod(d("2026-02-01"), d("2026-03-31")));

        assertThat(rows(result, 0)).extracting(r -> r.get("partyType") + " total=" + r.get("total") + " new=" + r.get("newInPeriod")
                + " verified=" + r.get("kycVerified") + " pending=" + r.get("kycPending") + " none=" + r.get("withoutPolicyInForce"))
            .containsExactly("CORPORATE total=1 new=0 verified=1 pending=0 none=0",
                "INDIVIDUAL total=2 new=1 verified=1 pending=1 none=1");
        Map<String, String> band3544 = rows(result, 1).stream().filter(r -> r.get("ageBand").equals("35-44")).findFirst().orElseThrow();
        assertThat(band3544.get("female")).isEqualTo("1");
        Map<String, String> band2534 = rows(result, 1).stream().filter(r -> r.get("ageBand").equals("25-34")).findFirst().orElseThrow();
        assertThat(band2534.get("male")).isEqualTo("1");
        assertThat(rows(result, 2)).extracting(r -> r.get("policiesInForce") + "=" + r.get("clients"))
            .containsExactly("0=1", "1=1", "2=0", "3 or more=0");
        assertThat(rows(result, 3)).singleElement().satisfies(row -> {
            assertThat(row.get("policyNumber")).isEqualTo("GRP-1");
            assertThat(row.get("holder")).isEqualTo("Upendo Cooperative");
            assertThat(row.get("livesCovered")).isEqualTo("11");
            assertThat(row.get("joined")).isEqualTo("1");
            assertThat(row.get("exited")).isEqualTo("1");
            assertThat(row.get("sumAssured")).isEqualTo("11000000.00");
        });
    }

    // ---- plumbing ----------------------------------------------------------------------------------------

    @Test
    void aDateFilterThatDoesNotMatchTheReportIsRefused() {
        assertThatThrownBy(() -> run("policies-in-force", ReportFilter.period(d("2026-01-01"), d("2026-01-31"))))
            .isInstanceOf(ReportParameterException.class).hasMessageContaining("asOf");
        assertThatThrownBy(() -> run("new-business", ReportFilter.period(d("2026-02-01"), d("2026-01-01"))))
            .isInstanceOf(ReportParameterException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> run("new-business", ReportFilter.period(d("2026-01-01"), d("2026-01-31"))
            .withCompareAsOf(d("2025-12-31")))).isInstanceOf(ReportParameterException.class);
        assertThatThrownBy(() -> run("no-such-report", ReportFilter.asOf(d("2026-01-31"))))
            .isInstanceOf(ReportNotFoundException.class);
    }

    @Test
    void theCatalogueListsReportsInBusinessOrder() {
        assertThat(reportingApi.catalogue()).extracting(ReportDefinitionView::key)
            .containsExactly("policies-in-force", "new-business", "lapse-rate", "persistency", "premium-collections", "clients");
    }

    @Test
    void theResultCarriesTitleSummaryAndGenerationTime() {
        ReportResult result = run("policies-in-force", ReportFilter.asOf(d("2026-01-31")));
        assertThat(result.title()).isEqualTo("Policies in force");
        assertThat(result.filterSummary()).contains("As of 2026-01-31");
        assertThat(result.generatedAt()).isNotNull();
        ReportSection section = result.sections().get(0);
        assertThat(section.totals()).isNotNull();
    }
}
