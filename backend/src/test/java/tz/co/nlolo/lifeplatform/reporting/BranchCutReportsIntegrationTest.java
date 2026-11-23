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
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Branch views (2026-11-23, backend/docs/superpowers/specs/2026-11-23-branch-views-design.md): every policy-and-claims
 * report can be cut by the policy's branch, and every company-wide report refuses a branch rather than ignoring it.
 *
 * <p>The two branches are deliberately unequal -- one Arusha policy against three in Dar es Salaam, different premiums,
 * claims and a lapse -- so a report that ignored the branch could not produce the same answer for both by chance.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(ReportingTestEvents.class)
class BranchCutReportsIntegrationTest {

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
        // The reporting set, plus the reinsurance schema that claims-net-of-reinsurance reads its recoveries from.
        String[] reinsurance = java.util.stream.IntStream.rangeClosed(1, 14)
            .mapToObj(v -> {
                try (var files = java.nio.file.Files.list(java.nio.file.Path.of("db-migrations/reinsurance"))) {
                    return files.map(p -> "db-migrations/reinsurance/" + p.getFileName())
                        .filter(p -> p.contains("/V" + v + "__")).findFirst().orElseThrow();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toArray(String[]::new);
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            java.util.stream.Stream.concat(java.util.Arrays.stream(ReportingTestMigrations.ALL),
                java.util.Arrays.stream(reinsurance)).distinct().toArray(String[]::new));
    }

    static final List<String> BRANCH_REPORTS = List.of("new-business", "policies-in-force", "persistency", "lapse-rate",
        "free-look-and-not-taken-up", "products", "clients", "premium-collections", "claim-outcomes",
        "claims-incurred", "claims-in-progress", "claims-turnaround", "claims-outstanding", "early-claims",
        "claims-approved-vs-paid", "claims-net-of-reinsurance", "loss-ratio");
    /** Commissions joined these on 2026-11-23: statements pay an agent across branches, so it cannot be cut whole. */
    static final List<String> COMPANY_WIDE = List.of("balance-sheet", "trial-balance", "general-ledger",
        "income-statement", "capital-adequacy", "solvency-forecast", "stress-tests", "reinsurance", "commissions");

    static final LocalDate START = LocalDate.of(2026, 1, 1);
    static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Autowired private ReportingTestEvents events;
    @Autowired private ReportingApi reportingApi;

    private final UUID tenant = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();

    @BeforeEach
    void seedTwoUnequalBranches() {
        TenantContext.set(tenant);
        sale("ARU-1", "ARU", "40000.00", "5000000.00");
        sale("DSM-1", "DSM", "10000.00", "1000000.00");
        sale("DSM-2", "DSM", "12000.00", "1200000.00");
        sale("DSM-3", "DSM", "15000.00", "1500000.00");
        events.collect(tenant, "ARU-1", "40000.00", LocalDate.of(2026, 2, 1), true);
        events.collect(tenant, "DSM-1", "10000.00", LocalDate.of(2026, 2, 1), true);
        events.collect(tenant, "DSM-2", "12000.00", LocalDate.of(2026, 2, 3), false);
        events.lapse(tenant, "DSM-3", LocalDate.of(2026, 4, 1));
        settledClaim("ARU-1", "100000.00");
        settledClaim("DSM-1", "900000.00");
        UUID open = events.claimRegistered(tenant, "DSM-2", "DEATH", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2));
        events.claimAssessed(tenant, open, LocalDate.of(2026, 5, 9));
    }

    @AfterEach
    void leaveTenant() {
        TenantContext.clear();
    }

    @Test
    void every_policy_and_claims_report_is_cut_by_the_policys_branch() {
        Map<String, ReportDefinitionView> catalogue = reportingApi.catalogue().stream()
            .collect(Collectors.toMap(ReportDefinitionView::key, Function.identity()));
        List<String> problems = new ArrayList<>();
        for (String key : BRANCH_REPORTS) {
            ReportDefinitionView d = catalogue.get(key);
            if (d == null) { problems.add(key + ": not in the catalogue"); continue; }
            if (d.companyWide()) { problems.add(key + ": marked company-wide"); continue; }
            try {
                ReportFilter base = filterFor(d);
                String arusha = reportingApi.run(key, base.withBranchCode("ARU")).sections().toString();
                String dar = reportingApi.run(key, base.withBranchCode("DSM")).sections().toString();
                if (arusha.equals(dar)) problems.add(key + ": the same for Arusha and Dar es Salaam");
                if (arusha.contains("DSM-1") || arusha.contains("DSM-2") || arusha.contains("DSM-3")) {
                    problems.add(key + ": the Arusha cut names a Dar es Salaam policy");
                }
            } catch (RuntimeException e) {
                problems.add(key + ": " + e.getMessage());
            }
        }
        assertThat(problems).as("reports that ignore or break on a branch").isEmpty();
    }

    @Test
    void every_company_wide_report_refuses_a_branch_rather_than_ignoring_it() {
        Map<String, ReportDefinitionView> catalogue = reportingApi.catalogue().stream()
            .collect(Collectors.toMap(ReportDefinitionView::key, Function.identity()));
        for (String key : COMPANY_WIDE) {
            ReportDefinitionView d = catalogue.get(key);
            assertThat(d).as(key).isNotNull();
            assertThat(d.companyWide()).as(key + " is company-wide").isTrue();
            assertThatThrownBy(() -> reportingApi.run(key, filterFor(d).withBranchCode("ARU")))
                .as(key).isInstanceOf(ReportParameterException.class).hasMessageContaining("is company-wide");
        }
    }

    @Test
    void the_summary_names_the_branch_on_screen_and_its_code_in_exports() {
        ReportResult result = reportingApi.run("new-business",
            ReportFilter.period(START, END).withBranchCode("ARU"));
        assertThat(result.filterSummary()).contains(" branch").doesNotContain("Branch:");
        assertThat(result.exportSummary()).contains("Branch: ");
        assertThat(result.exportSummary()).containsAnyOf("(ARU)", "Branch: ARU");
        ReportResult all = reportingApi.run("new-business", ReportFilter.period(START, END));
        assertThat(all.filterSummary()).doesNotContain(" branch");
        assertThat(all.exportSummary()).doesNotContain("Branch:");
    }

    @Test
    void the_branchs_policy_numbers() {
        assertThat(reportingApi.policyNumbersInBranch("ARU")).containsExactly("ARU-1");
        assertThat(reportingApi.policyNumbersInBranch("DSM")).containsExactlyInAnyOrder("DSM-1", "DSM-2", "DSM-3");
        assertThat(reportingApi.policyNumbersInBranch("MOS")).isEmpty();
    }

    private void sale(String number, String branch, String premium, String sumAssured) {
        UUID holder = UUID.randomUUID();
        events.client(tenant, holder, "INDIVIDUAL", "VERIFIED", "Holder " + number, LocalDate.of(2025, 12, 1),
            LocalDate.of(1985, 3, 3), "FEMALE");
        events.sell(tenant, Sale.of(number, product, LocalDate.of(2026, 1, 15)).branch(branch)
            .premium("MONTHLY", premium).holder(holder));
    }

    private void settledClaim(String policyNumber, String amount) {
        UUID claim = events.claimRegistered(tenant, policyNumber, "DEATH", LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 2));
        events.claimAssessed(tenant, claim, LocalDate.of(2026, 3, 5));
        events.claimApproved(tenant, claim, policyNumber, "DEATH", LocalDate.of(2026, 3, 1), amount,
            LocalDate.of(2026, 3, 10));
        events.claimSettlementRequested(tenant, claim, policyNumber, amount, LocalDate.of(2026, 3, 12));
        events.claimSettled(tenant, claim, policyNumber, amount, amount, LocalDate.of(2026, 3, 20));
    }

    /** The dates each kind of report asks for, covering everything seeded. */
    private static ReportFilter filterFor(ReportDefinitionView d) {
        return switch (d.dateFilterKind()) {
            case AS_OF -> ReportFilter.asOf(END);
            case PERIOD -> ReportFilter.period(START, END);
            case COHORT -> ReportFilter.cohort(YearMonth.from(START), YearMonth.from(END));
            case AS_OF_AND_PERIOD -> ReportFilter.asOf(END).withPeriod(START, END);
            case MONTH -> ReportFilter.month(YearMonth.from(END));
            case MONTH_RANGE -> ReportFilter.months(YearMonth.from(START), YearMonth.from(END));
        };
    }
}
