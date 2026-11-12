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
import tz.co.nlolo.lifeplatform.reporting.api.CategoryAmount;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyAmount;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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

    /**
     * 2026-11-09, batch 4: the movement from a month earlier reconciles with the in-force count on both dates -- the
     * moves are read off the same status timeline. Four in force at the end of February; in March one is sold, one
     * lapses, a lapsed one is reinstated, one is suspended (still in force), one cancelled in free-look.
     */
    @Test
    void theBooksMovementReconcilesTheOpeningWithTheClosing() {
        events.sell(tenant, Sale.of("M-A", product, d("2026-01-05")));
        events.sell(tenant, Sale.of("M-B", product, d("2026-01-06")));
        events.sell(tenant, Sale.of("M-C", product, d("2026-01-07")));
        events.sell(tenant, Sale.of("M-D", product, d("2026-02-20")));
        events.sell(tenant, Sale.of("M-E", product, d("2026-01-08")));
        events.lapse(tenant, "M-E", d("2026-02-10"));
        events.sell(tenant, Sale.of("M-F", product, d("2026-03-04")));
        events.lapse(tenant, "M-A", d("2026-03-10"));
        events.reinstate(tenant, "M-E", d("2026-03-12"));
        events.suspend(tenant, "M-B", d("2026-03-15"));
        events.cancelFreeLook(tenant, "M-D", d("2026-03-16"));

        ReportResult result = run("policies-in-force",
            ReportFilter.asOf(d("2026-03-31")).withCompareWith(tz.co.nlolo.lifeplatform.reporting.api.CompareWith.PRIOR_PERIOD));

        assertThat(rows(result, 1)).extracting(r -> r.get("line") + " " + r.get("policies")).containsExactly(
            "In force on 2026-02-28 4", "New policies 1", "Reinstated 1", "Lapsed -1",
            "Cancelled in free-look -1", "In force on 2026-03-31 4");
        assertThat(rows(result, 0)).singleElement().satisfies(row -> assertThat(row.get("policies")).isEqualTo("4"));
        assertThat(result.figures().get(0).prior()).isEqualTo("4");
        assertThat(result.chart().unit()).isEqualTo("COUNT");
    }

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

    // 2026-11-08, the user: a savings account's "sum assured" is its opening deposit, so it disappears from sum assured.
    @Test
    void savingsAccountsAndFixedDepositsCountAsPoliciesButAddNothingToSumAssured() {
        events.sell(tenant, Sale.of("S-TERM", product, d("2026-01-05")));
        events.sell(tenant, Sale.of("S-SAV", product, d("2026-01-06")).portfolio("SAV").premium("SINGLE", "20000.00"));
        events.sell(tenant, Sale.of("S-DEP", product, d("2026-01-07")).portfolio("DEP").premium("SINGLE", "500000.00"));

        assertThat(rows(run("policies-in-force", ReportFilter.asOf(d("2026-03-31"))), 0)).singleElement().satisfies(row -> {
            assertThat(row.get("policies")).isEqualTo("3");
            assertThat(row.get("sumAssured")).isEqualTo("1000000.00");
        });
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
            // No method on the event (an older one), no payer: the old guess, labelled as such (2026-11-11).
            .containsExactly("2026-01 Cash (older records) 1 4000.00", "2026-01 Mobile money 2 20000.00",
                "2026-02 Mobile money 1 10000.00");
        assertThat(result.sections().get(0).totals().get("amount")).isEqualTo("34000.00");
        // 2026-11-09, batch 4: the payment-method split now sits between the months and the top policies.
        assertThat(rows(result, 2)).extracting(r -> r.get("policyNumber")).containsExactly("K-1", "K-2");
    }

    /**
     * One number per month, for a dashboard's figure and chart. The invariant the dashboard rests on is the second
     * assertion: the monthly points add up to the premium-collections report's own total for the same period, so a
     * chart drawn from these can never disagree with the report a finance officer opens from it.
     */
    @Test
    void monthlyCollectionsAgreeWithTheCollectionsReportsOwnTotal() {
        events.sell(tenant, Sale.of("M-1", product, d("2026-01-02")));
        events.collect(tenant, "M-1", "10000.00", d("2026-01-05"), true);
        events.collect(tenant, "M-1", "4000.00", d("2026-01-26"), false);
        events.collect(tenant, "M-1", "10000.00", d("2026-02-05"), true);

        ReportResult report = run("premium-collections", ReportFilter.period(d("2026-01-01"), d("2026-02-28")));
        String currency = rows(report, 0).get(0).get("currency");

        List<MonthlyAmount> months = reportingApi.monthlyPremiumCollections(YearMonth.of(2026, 1), YearMonth.of(2026, 2));

        assertThat(months).extracting(m -> m.month() + " " + m.currency() + " " + m.amount().toPlainString())
            .containsExactly("2026-01 " + currency + " 14000.00", "2026-02 " + currency + " 10000.00");
        assertThat(months.stream().map(MonthlyAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString())
            .isEqualTo(report.sections().get(0).totals().get("amount"));
    }

    /**
     * The ranged dashboards' three reads (2026-10-23). The product lines of a span add up to the same span's monthly
     * total, and the agent scope is a filter: null is everyone, an empty team is nobody -- never everybody.
     */
    @Test
    void collectionsByCategoryIssuesByMonthAndTheBookByCategoryForATeam() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        events.sell(tenant, Sale.of("C-1", product, d("2026-01-02")).agent(mine));
        events.sell(tenant, Sale.of("C-2", product, d("2026-02-03")).agent(mine).scheme(5));
        events.sell(tenant, Sale.of("C-3", product, d("2026-02-10")).agent(theirs));
        events.collect(tenant, "C-1", "10000.00", d("2026-01-05"), true);
        events.collect(tenant, "C-2", "30000.00", d("2026-02-05"), true);
        events.collect(tenant, "C-3", "5000.00", d("2026-02-12"), true);
        events.lapse(tenant, "C-3", d("2026-03-01"));

        List<CategoryAmount> lines = reportingApi.premiumCollectionsByCategory(YearMonth.of(2026, 1), YearMonth.of(2026, 2));
        assertThat(lines).extracting(c -> c.category() + " " + c.amount().toPlainString())
            .containsExactly("GROUP_LIFE 30000.00", "TERM_LIFE 15000.00");
        BigDecimal monthly = reportingApi.monthlyPremiumCollections(YearMonth.of(2026, 1), YearMonth.of(2026, 2)).stream()
            .map(MonthlyAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lines.stream().map(CategoryAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(monthly);

        assertThat(reportingApi.policiesIssuedByMonth(YearMonth.of(2026, 1), YearMonth.of(2026, 3), null))
            .extracting(m -> m.month() + " " + m.count()).containsExactly("2026-01 1", "2026-02 2");
        assertThat(reportingApi.policiesIssuedByMonth(YearMonth.of(2026, 1), YearMonth.of(2026, 3), Set.of(mine)))
            .extracting(m -> m.month() + " " + m.count()).containsExactly("2026-01 1", "2026-02 1");
        assertThat(reportingApi.policiesIssuedByMonth(YearMonth.of(2026, 1), YearMonth.of(2026, 3), Set.of())).isEmpty();

        // C-3 lapsed in March: in force for nobody on 31 March, and never in my book.
        assertThat(reportingApi.policiesInForceByCategory(d("2026-03-31"), null))
            .extracting(c -> c.category() + " " + c.count()).containsExactlyInAnyOrder("GROUP_LIFE 1", "TERM_LIFE 1");
        assertThat(reportingApi.policiesInForceByCategory(d("2026-02-28"), Set.of(theirs)))
            .extracting(c -> c.category() + " " + c.count()).containsExactly("TERM_LIFE 1");
        assertThat(reportingApi.policiesInForceByCategory(d("2026-03-31"), Set.of())).isEmpty();
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

    // ---- products ----------------------------------------------------------------------------------------

    @Test
    void productsSummariseEachProductAndListThePoliciesBehindIt() {
        UUID other = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        events.client(tenant, holder, "INDIVIDUAL", "VERIFIED", "Asha Mwita", d("2025-12-01"), d("1990-01-01"), "FEMALE");
        events.sell(tenant, Sale.of("P-A", product, d("2026-01-05")).holder(holder));
        events.sell(tenant, Sale.of("P-B", product, d("2026-01-06")).premium("QUARTERLY", "30000.00"));
        events.sell(tenant, Sale.of("P-C", product, d("2026-01-07")));
        events.lapse(tenant, "P-C", d("2026-03-01"));
        events.sell(tenant, Sale.of("P-S", product, d("2026-01-08")).premium("SINGLE", "500000.00"));
        events.sell(tenant, Sale.of("P-D", other, d("2026-01-09")));
        // Sold after the as-of date: not yet a policy on that day, in either section.
        events.sell(tenant, Sale.of("P-LATE", product, d("2026-04-01")));

        ReportResult result = run("products", ReportFilter.asOf(d("2026-03-31")));

        Map<String, String> mine = rows(result, 0).stream()
            .filter(r -> r.get("product").equals(product.toString())).findFirst().orElseThrow();
        assertThat(mine.get("written")).isEqualTo("4");
        assertThat(mine.get("inForce")).isEqualTo("3");
        assertThat(mine.get("lapsed")).isEqualTo("1");
        // P-A, P-B and P-S are on cover; P-C lapsed.
        assertThat(mine.get("sumAssured")).isEqualTo("3000000.00");
        // P-A 120000 + P-B 120000. P-S pays once, so it is no part of an annual premium...
        assertThat(mine.get("annualisedPremium")).isEqualTo("240000.00");
        // ...and no part of the average either: 240000 over the two policies that pay regularly.
        assertThat(mine.get("averagePremium")).isEqualTo("120000.00");
        assertThat(result.sections().get(0).totals().get("written")).isEqualTo("5");
        assertThat(result.sections().get(0).totals().get("inForce")).isEqualTo("4");

        List<Map<String, String>> policies = rows(result, 1);
        assertThat(policies).extracting(r -> r.get("policyNumber"))
            .containsExactlyInAnyOrder("P-A", "P-B", "P-C", "P-S", "P-D");
        assertThat(policies).filteredOn(r -> r.get("policyNumber").equals("P-A")).singleElement().satisfies(r -> {
            assertThat(r.get("product")).isEqualTo(product.toString());
            assertThat(r.get("client")).isEqualTo("Asha Mwita");
            assertThat(r.get("status")).isEqualTo("Active");
            assertThat(r.get("activatedOn")).isEqualTo("2026-01-05");
            assertThat(r.get("sumAssured")).isEqualTo("1000000.00");
            assertThat(r.get("premium")).isEqualTo("10000.00");
            assertThat(r.get("frequency")).isEqualTo("Monthly");
            assertThat(r.get("agent")).isEqualTo("Direct (no agent)");
            assertThat(r.get("branch")).isEqualTo("DSM-01");
        });
        // Status is as of the report date, not as of today.
        assertThat(policies).filteredOn(r -> r.get("policyNumber").equals("P-C")).singleElement()
            .satisfies(r -> assertThat(r.get("status")).isEqualTo("Lapsed"));
        // A holder the read model has no record of is blank -- the console shows "—" -- never a raw id.
        assertThat(policies).filteredOn(r -> r.get("policyNumber").equals("P-B")).singleElement()
            .satisfies(r -> assertThat(r.get("client")).isEmpty());
    }

    @Test
    void productsNarrowToTheChosenProduct() {
        UUID other = UUID.randomUUID();
        events.sell(tenant, Sale.of("P-A", product, d("2026-01-05")));
        events.sell(tenant, Sale.of("P-D", other, d("2026-01-09")));

        ReportResult result = run("products", ReportFilter.asOf(d("2026-03-31")).withProductId(product));

        assertThat(rows(result, 0)).singleElement()
            .satisfies(r -> assertThat(r.get("product")).isEqualTo(product.toString()));
        assertThat(rows(result, 1)).extracting(r -> r.get("policyNumber")).containsExactly("P-A");
    }

    /** Drill-down (2026-11-11, analytics plan 2): each policy, its client and its agent open their own record. */
    @Test
    void thePolicyRegisterLinksEachPolicyItsClientAndItsAgent() {
        UUID person = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        events.client(tenant, person, "INDIVIDUAL", "VERIFIED", "Asha", d("2025-12-01"), d("1990-01-01"), "FEMALE");
        events.sell(tenant, Sale.of("L-1", product, d("2026-01-05")).holder(person).agent(agent));

        ReportResult result = run("products", ReportFilter.asOf(d("2026-03-31")).withProductId(product));

        assertThat(result.sections().get(1).columns())
            .extracting(ReportColumn::key, ReportColumn::link)
            .contains(tuple("policyNumber", "POLICY"), tuple("client", "CLIENT"), tuple("agent", "AGENT"));
        assertThat(rows(result, 1)).singleElement().satisfies(row -> {
            assertThat(row.get("policyNumber")).isEqualTo("L-1");
            assertThat(row.get("_clientId")).isEqualTo(person.toString());
            assertThat(row.get("_agentId")).isEqualTo(agent.toString());
        });
    }

    /**
     * How fresh the figures are (2026-11-11, analytics plan 2): a report on the reports' copy says the newest change it
     * took in; a ledger report reads live and says nothing of the copy; every report says what it reads.
     */
    @Test
    void aReportOnTheCopySaysTheLastChangeItTookIn() {
        events.sell(tenant, Sale.of("F-1", product, d("2026-01-05")));

        assertThat(run("policies-in-force", ReportFilter.asOf(d("2026-01-31"))).dataUpTo())
            .isNotNull().isBeforeOrEqualTo(java.time.Instant.now());
        assertThat(run("trial-balance", ReportFilter.month(YearMonth.of(2026, 9))).dataUpTo()).isNull();
        assertThat(reportingApi.catalogue()).allSatisfy(d -> assertThat(d.sources()).as(d.key()).isNotEmpty());
    }

    @Test
    void theCatalogueListsReportsInBusinessOrder() {
        assertThat(reportingApi.catalogue()).extracting(ReportDefinitionView::key)
            // Management first, then accounting -- ReportingApiImpl.ORDER, which the Accounting batch (2026-10-14)
            // extended with its four statements. This assertion was left at the seven management reports by that
            // batch and had been failing on main ever since; nothing else asserts the catalogue is complete.
            .containsExactly("policies-in-force", "products", "new-business", "lapse-rate", "persistency",
                "free-look-and-not-taken-up",
                "premium-collections", "clients",
                "trial-balance", "general-ledger", "balance-sheet", "income-statement",
                "claims-incurred", "loss-ratio", "claim-outcomes", "claims-approved-vs-paid", "claims-turnaround",
                "early-claims", "claims-outstanding", "claims-net-of-reinsurance",
                "capital-adequacy", "stress-tests", "solvency-forecast", "reinsurance", "commissions");
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

    // ---- claims (2026-11-09, the reports redesign batch 2) -----------------------------------------------------------

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Map<String, Object> claimRow(UUID claimId) {
        return jdbc.queryForMap("SELECT * FROM reporting.report_claim WHERE tenant_id = ? AND claim_id = ?", tenant, claimId);
    }

    @Test
    void aClaimsLifeIsOneRowWithEveryStepDated() {
        events.sell(tenant, Sale.of("P-CL", product, d("2025-01-05")));
        UUID claim = events.claimRegistered(tenant, "P-CL", "DEATH", d("2026-02-20"), d("2026-03-01"));
        events.claimApproved(tenant, claim, "P-CL", "DEATH", d("2026-02-20"), "5000000.00", d("2026-03-10"));
        events.claimSettlementRequested(tenant, claim, "P-CL", "4800000.00", d("2026-03-11"));
        events.claimSettled(tenant, claim, "P-CL", "5000000.00", "4800000.00", d("2026-03-15"));

        Map<String, Object> row = claimRow(claim);
        assertThat(row.get("claim_type")).isEqualTo("DEATH");
        assertThat(row.get("registered_on").toString()).isEqualTo("2026-03-01");
        assertThat(row.get("approved_on").toString()).isEqualTo("2026-03-10");
        assertThat((java.math.BigDecimal) row.get("approved_amount")).isEqualByComparingTo("5000000.00");
        assertThat((java.math.BigDecimal) row.get("payable_amount")).isEqualByComparingTo("4800000.00");
        assertThat((java.math.BigDecimal) row.get("paid_amount")).isEqualByComparingTo("4800000.00");
        assertThat(row.get("settled_on").toString()).isEqualTo("2026-03-15");
    }

    /**
     * A small book: in September two death claims of 4m and 6m approved; in October three -- 4m, 5m and one large
     * 25m -- one disability declined, and one still open.
     */
    private void twoMonthsOfClaims() {
        events.sell(tenant, Sale.of("P-1", product, d("2025-06-01")));
        events.sell(tenant, Sale.of("P-2", product, d("2026-03-01")));
        claim("P-1", "DEATH", "2026-08-25", "2026-09-01", "2026-09-05", "4000000.00", "2026-09-20");
        claim("P-1", "DEATH", "2026-08-26", "2026-09-02", "2026-09-10", "6000000.00", null);
        claim("P-2", "DEATH", "2026-09-28", "2026-10-01", "2026-10-03", "4000000.00", "2026-10-10");
        claim("P-2", "DEATH", "2026-09-29", "2026-10-02", "2026-10-06", "5000000.00", "2026-10-30");
        claim("P-1", "DEATH", "2026-09-30", "2026-10-03", "2026-10-08", "25000000.00", null);
        UUID declined = events.claimRegistered(tenant, "P-2", "DISABILITY", d("2026-10-01"), d("2026-10-04"));
        events.claimRejected(tenant, declined, "P-2", "DISABILITY", "Non-disclosure", d("2026-10-12"));
        events.claimRegistered(tenant, "P-2", "DEATH", d("2026-10-20"), d("2026-10-21"));
    }

    private void claim(String policy, String type, String event, String registered, String approved, String amount,
                       String paid) {
        UUID id = events.claimRegistered(tenant, policy, type, d(event), d(registered));
        events.claimApproved(tenant, id, policy, type, d(event), amount, d(approved));
        if (paid != null) {
            events.claimSettlementRequested(tenant, id, policy, amount, d(paid));
            events.claimSettled(tenant, id, policy, amount, amount, d(paid));
        }
    }

    private static ReportFilter october() {
        return ReportFilter.months(java.time.YearMonth.of(2026, 10), java.time.YearMonth.of(2026, 10))
            .withCompareWith(tz.co.nlolo.lifeplatform.reporting.api.CompareWith.PRIOR_PERIOD);
    }

    private static String figure(ReportResult r, String key) {
        return r.figures().stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow().value();
    }

    @Test
    void claimsIncurredSplitsTheChangeIntoMoreClaimsBiggerClaimsAndLargeOnes() {
        twoMonthsOfClaims();

        ReportResult r = run("claims-incurred", october());

        assertThat(figure(r, "incurred")).isEqualTo("34000000.00");
        assertThat(r.figures().get(0).prior()).isEqualTo("10000000.00");
        assertThat(figure(r, "claims")).isEqualTo("3");
        assertThat(figure(r, "large")).isEqualTo("1");
        // Ordinary claims: Sep 2 x 5m = 10m, Oct 2 x 4.5m = 9m. Count effect 0; severity -1m; large +25m.
        List<tz.co.nlolo.lifeplatform.reporting.api.ReportChart.Bar> bars = r.chart().bars();
        assertThat(bars.get(0).value()).isEqualTo("10000000.00");
        assertThat(bars.get(1).value()).isEqualTo("0.00");
        assertThat(bars.get(2).kind()).isEqualTo("DOWN");
        assertThat(bars.get(2).value()).isEqualTo("1000000.00");
        assertThat(bars.get(3).value()).isEqualTo("25000000.00");
        assertThat(bars.get(4).value()).isEqualTo("34000000.00");

        // The tree: the product opens to Death, which opens to its three claims, each level stating its sum.
        tz.co.nlolo.lifeplatform.reporting.api.ReportParts byType =
            reportingApi.parts("claims-incurred", "product:" + product, october(), 0);
        assertThat(byType.rows()).singleElement().satisfies(row -> assertThat(row.get("line")).isEqualTo("Death"));
        assertThat(byType.balanced()).isTrue();
        tz.co.nlolo.lifeplatform.reporting.api.ReportParts death =
            reportingApi.parts("claims-incurred", byType.rows().get(0).get("_lineId"), october(), 0);
        assertThat(death.rows()).hasSize(3);
        assertThat(death.check()).isEqualTo("3 claims = 34,000,000.00");
    }

    @Test
    void outcomesTurnaroundAndTheOpenClaimsMovementAddUp() {
        twoMonthsOfClaims();

        ReportResult outcomes = run("claim-outcomes", october());
        assertThat(figure(outcomes, "registered")).isEqualTo("5");
        assertThat(figure(outcomes, "declineRate")).isEqualTo("25.0");
        assertThat(rows(outcomes, 1)).singleElement().satisfies(row -> assertThat(row.get("line")).isEqualTo("Non-disclosure"));

        ReportResult outstanding = run("claims-outstanding", october());
        // Open at start: September's unpaid 6m claim. Registered 5, paid 2, declined 1 -> open 3.
        assertThat(rows(outstanding, 0).get(0).get("count")).isEqualTo("1");
        assertThat(rows(outstanding, 0).get(5).get("count")).isEqualTo("3");
        assertThat(rows(outstanding, 0).get(6).get("line")).startsWith("✓ 1 + 5 + 0 − 2 − 1 = 3");
        assertThat(figure(outstanding, "unpaid")).isEqualTo("31000000.00");

        ReportResult paid = run("claims-approved-vs-paid", october());
        assertThat(figure(paid, "paid")).isEqualTo("9000000.00");

        ReportResult turnaround = run("claims-turnaround", october());
        // Paid in October: registered 1 Oct paid 10 Oct (9 days), registered 2 Oct paid 30 Oct (28 days).
        assertThat(figure(turnaround, "onTime")).isEqualTo("100.0");

        ReportResult early = run("early-claims", october());
        // P-2 started 2026-03-01: its October claims fell within a year of starting.
        assertThat(figure(early, "year1")).isEqualTo("4");
    }

    /**
     * Found on dev 2026-11-09: a declined claim that was reopened still counted as closed. Its decline stays where it
     * happened; from the reopening it is open again, and the movement shows it.
     */
    @Test
    void aReopenedClaimIsOpenAgainAndTheMovementStillAddsUp() {
        events.sell(tenant, Sale.of("P-R", product, d("2025-06-01")));
        UUID claim = events.claimRegistered(tenant, "P-R", "DEATH", d("2026-09-01"), d("2026-09-02"));
        events.claimRejected(tenant, claim, "P-R", "DEATH", "Exclusion period", d("2026-09-20"));
        events.claimReopened(tenant, claim, "P-R", "DEATH", d("2026-10-05"));

        ReportResult september = run("claims-outstanding", ReportFilter.months(java.time.YearMonth.of(2026, 9),
            java.time.YearMonth.of(2026, 9)));
        assertThat(rows(september, 0).get(5).get("count")).as("declined in September: closed").isEqualTo("0");

        ReportResult r = run("claims-outstanding", october());
        assertThat(rows(r, 0).get(2).get("count")).as("reopened in October").isEqualTo("1");
        assertThat(rows(r, 0).get(5).get("count")).as("open again at the end of October").isEqualTo("1");
        assertThat(rows(r, 0).get(6).get("line")).startsWith("✓ 0 + 0 + 1 − 0 − 0 = 1");
        // Its decline is history: it happened in September.
        assertThat(figure(run("claim-outcomes", october()), "declined")).isEqualTo("0");
    }

    @Test
    void aDeclinedClaimKeepsItsReasonAndAnEarlyEventStillMakesARow() {
        UUID claim = UUID.randomUUID();
        // The approval arrives before the registration (a replay out of order): the row is made, then completed.
        events.claimApproved(tenant, claim, "P-X", "DISABILITY", d("2026-01-02"), "100.00", d("2026-01-20"));
        UUID declined = events.claimRegistered(tenant, "P-Y", "DEATH", d("2026-01-03"), d("2026-01-04"));
        events.claimRejected(tenant, declined, "P-Y", "DEATH", "Non-disclosure", d("2026-01-25"));

        assertThat(claimRow(claim).get("approved_on").toString()).isEqualTo("2026-01-20");
        assertThat(claimRow(declined).get("reject_reason")).isEqualTo("Non-disclosure");
        assertThat(claimRow(declined).get("rejected_on").toString()).isEqualTo("2026-01-25");
    }
}
