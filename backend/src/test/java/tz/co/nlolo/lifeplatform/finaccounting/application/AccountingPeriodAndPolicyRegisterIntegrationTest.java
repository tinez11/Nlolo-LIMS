package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyElectionInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyElectionView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyRegisterStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PolicyRegisterBaseline;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IFRS 17 I1: accounting periods (spec §5.4) and the accounting policy register (spec §3), through the module's API
 * against a real database -- the period lock's two preconditions, the database refusing a journal in a locked period,
 * the second-person rules, and a journal recording the register version an approval made current.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class AccountingPeriodAndPolicyRegisterIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "finaccounting_it_password";
    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        // The same list as FinaccountingApiIntegrationTest, for the same reasons (gl_posting's partition controls).
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
            "db-migrations/product/V11__frequency_loading.sql",
            "db-migrations/product/V12__tira_filing.sql",
            "db-migrations/product/V13__benefit_calculation_method.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/policyloan/V8__interest_month_published.sql",
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql",
            "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql",
            "db-migrations/finaccounting/V7__q4_2026_partitions.sql",
            "db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql",
            "db-migrations/finaccounting/V11__groups_and_policy_classification.sql",
            "db-migrations/finaccounting/V12__unposted_events_and_paa_earning.sql",
            "db-migrations/finaccounting/V13__disbursement_method.sql",
            "db-migrations/finaccounting/V14__manual_journals.sql",
            "db-migrations/finaccounting/V15__engine_period_cycle.sql",
            "db-migrations/finaccounting/V16__expense_allocation.sql",
            "db-migrations/finaccounting/V17__year_end_close.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private FinaccountingApi api;
    @Autowired private FinaccountingApiImpl impl;
    @Autowired private ChartOfAccountSeeder seeder;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    // ---- periods ----

    @Test
    void aPeriodLocksOnlyAfterEveryEarlierPeriodWithPostingsIsLocked() {
        UUID tenant = tenantWithChart();
        post(tenant, "p-aug", "2026-08", "1140", "2122", "15000.00");
        post(tenant, "p-sep", "2026-09", "1140", "2122", "9000.00");

        as(tenant);
        api.startClosing("2026-09", "alice");
        assertThatThrownBy(() -> api.lockPeriod("2026-09", "alice"))
            .isInstanceOf(PeriodStateException.class).hasMessage("Period 2026-08 must be locked first");

        api.startClosing("2026-08", "alice");
        assertThat(api.lockPeriod("2026-08", "alice").status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(api.lockPeriod("2026-09", "alice").status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(api.periods()).extracting(v -> v.period()).containsExactly("2026-09", "2026-08");
    }

    @Test
    void aPeriodDoesNotLockWhileAClearingAccountHoldsABalance() {
        UUID tenant = tenantWithChart();
        post(tenant, "clr-in", "2026-07", "9110", "1140", "500.00");

        as(tenant);
        api.startClosing("2026-07", "alice");
        assertThatThrownBy(() -> api.lockPeriod("2026-07", "alice"))
            .isInstanceOf(PeriodStateException.class)
            .hasMessage("Clearing account 9110 holds 500.00 TZS in 2026-07; clearing accounts must return to zero "
                + "before the period locks");

        // A closing period takes no event journal; the month-end step that clears it is the platform's own.
        assertThatThrownBy(() -> post(tenant, "clr-out", "2026-07", "1140", "9110", "500.00"))
            .hasStackTraceContaining("LEDGER_PERIOD_CLOSING");
        post(tenant, "clr-out", "2026-07", "1140", "9110", "500.00", JournalSource.SYSTEM);
        as(tenant);
        assertThat(api.lockPeriod("2026-07", "alice").status()).isEqualTo(PeriodStatus.LOCKED);
    }

    @Test
    void theDatabaseRefusesAJournalInALockedPeriodAndASecondPersonReopensIt() {
        UUID tenant = tenantWithChart();
        as(tenant);
        api.startClosing("2026-06", "alice");
        api.lockPeriod("2026-06", "alice");

        assertThatThrownBy(() -> post(tenant, "late", "2026-06", "1140", "2122",
            "100.00")).hasStackTraceContaining("LEDGER_PERIOD_LOCKED");

        as(tenant);
        assertThatThrownBy(() -> api.requestReopen("2026-06", " ", "alice"))
            .isInstanceOf(FinaccountingValidationException.class);
        api.requestReopen("2026-06", "Late premium receipt found on the bank statement", "alice");
        assertThatThrownBy(() -> api.approveReopen("2026-06", "alice"))
            .isInstanceOf(PeriodStateException.class).hasMessage("A second person approves reopening a period");
        var reopened = api.approveReopen("2026-06", "bob");
        assertThat(reopened.status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(reopened.reopenRequestedBy()).isEqualTo("alice");
        assertThat(reopened.reopenedBy()).isEqualTo("bob");

        post(tenant, "late", "2026-06", "1140", "2122", "100.00");
    }

    @Test
    void aPeriodOutOfFormatIsRefused() {
        as(tenantWithChart());
        assertThatThrownBy(() -> api.period("2026-13")).isInstanceOf(FinaccountingValidationException.class)
            .hasMessage("A period is YYYY-MM, for example 2026-10");
    }

    // ---- policy register ----

    @Test
    void theBaselineIsInForceForATenantThatHasPostedNothing() {
        UUID tenant = UUID.randomUUID();
        as(tenant);
        LocalDate today = LocalDate.now(CIVIL);
        assertThat(api.policyElectionInForce("MEASUREMENT_MODEL", "ULIP", today)).get()
            .extracting(PolicyElectionView::value).isEqualTo("VFA");
        // a company-wide election answers for any scope
        assertThat(api.policyElectionInForce("PREMIUM_BILLING", "TERM", today)).get()
            .extracting(PolicyElectionView::scope).isEqualTo("*");
        assertThat(api.policyElections(today)).hasSize(PolicyRegisterBaseline.ROWS.size())
            .allSatisfy(e -> assertThat(e.status()).isEqualTo("APPROVED"));
    }

    @Test
    void anApprovedElectionIsTheNextVersionAndTheNextJournalRecordsIt() {
        UUID tenant = tenantWithChart();
        as(tenant);
        LocalDate today = LocalDate.now(CIVIL);
        int baselineVersion = PolicyRegisterBaseline.ROWS.size();

        PolicyElectionView proposed = api.proposePolicyElection(
            new PolicyElectionInput("MEASUREMENT_MODEL", "TERM", "PAA", today, "Book now one-year renewable"), "alice");
        assertThat(proposed.status()).isEqualTo("PROPOSED");
        assertThat(proposed.registerVersion()).isNull();

        assertThatThrownBy(() -> api.approvePolicyElection(proposed.electionId(), "AC-2026-14", "alice"))
            .isInstanceOf(PolicyRegisterStateException.class)
            .hasMessage("A second person approves an accounting policy election");
        PolicyElectionView approved = api.approvePolicyElection(proposed.electionId(), "AC-2026-14", "bob");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.registerVersion()).isEqualTo(baselineVersion + 1);
        assertThat(approved.signOffRef()).isEqualTo("AC-2026-14");
        assertThat(api.policyElectionInForce("MEASUREMENT_MODEL", "TERM", today)).get()
            .extracting(PolicyElectionView::value).isEqualTo("PAA");

        UUID journalId = post(tenant, "after-election", "2026-10", "1140", "2122",
            "100.00");
        as(tenant);
        assertThat(api.getJournalEntry(journalId).policyRegisterVersion()).isEqualTo(baselineVersion + 1);
    }

    @Test
    void anElectionCannotReachIntoThePastOrTakeAValueItsKeyDoesNotPermit() {
        as(UUID.randomUUID());
        LocalDate today = LocalDate.now(CIVIL);
        assertThatThrownBy(() -> api.proposePolicyElection(
            new PolicyElectionInput("OCI_OPTION", "*", "ON", today.minusDays(1), null), "alice"))
            .isInstanceOf(FinaccountingValidationException.class)
            .hasMessage("An election applies from today or later; a past change is a restatement, made through journals");
        assertThatThrownBy(() -> api.proposePolicyElection(
            new PolicyElectionInput("OCI_OPTION", "*", "MAYBE", today, null), "alice"))
            .isInstanceOf(FinaccountingValidationException.class)
            .hasMessage("MAYBE is not a permitted value for OCI_OPTION");
        assertThatThrownBy(() -> api.proposePolicyElection(
            new PolicyElectionInput("NO_SUCH_KEY", "*", "ON", today, null), "alice"))
            .isInstanceOf(FinaccountingValidationException.class);
    }

    @Test
    void anElectionApprovedForTomorrowIsListedAsScheduledNotInForce() {
        as(UUID.randomUUID());
        LocalDate today = LocalDate.now(CIVIL);
        PolicyElectionView proposed = api.proposePolicyElection(
            new PolicyElectionInput("OCI_OPTION", "*", "ON", today.plusDays(1), null), "alice");
        api.approvePolicyElection(proposed.electionId(), "AC-15", "bob");

        assertThat(api.policyElectionInForce("OCI_OPTION", "*", today)).get()
            .extracting(PolicyElectionView::value).isEqualTo("OFF");
        assertThat(api.policyElectionInForce("OCI_OPTION", "*", today.plusDays(1))).get()
            .extracting(PolicyElectionView::value).isEqualTo("ON");
        // In force first (the baseline's OFF), then the scheduled ON -- shown before the day it applies.
        assertThat(api.policyElections(today))
            .filteredOn(e -> "OCI_OPTION".equals(e.key()))
            .extracting(PolicyElectionView::value).containsExactly("OFF", "ON");
    }

    @Test
    void aSecondElectionForTheSameKeyScopeAndDayIsRefusedNotA500() {
        as(UUID.randomUUID());
        LocalDate tomorrow = LocalDate.now(CIVIL).plusDays(1);
        PolicyElectionView first = api.proposePolicyElection(
            new PolicyElectionInput("OCI_OPTION", "*", "ON", tomorrow, null), "alice");
        PolicyElectionView second = api.proposePolicyElection(
            new PolicyElectionInput("OCI_OPTION", "*", "OFF", tomorrow, null), "alice");
        api.approvePolicyElection(first.electionId(), "AC-16", "bob");

        String refusal = "An approved OCI_OPTION election for * already takes effect on " + tomorrow
            + "; propose the change from another date";
        assertThatThrownBy(() -> api.approvePolicyElection(second.electionId(), "AC-17", "bob"))
            .isInstanceOf(PolicyRegisterStateException.class).hasMessage(refusal);
        assertThatThrownBy(() -> api.proposePolicyElection(
            new PolicyElectionInput("OCI_OPTION", null, "OFF", tomorrow, null), "alice"))
            .isInstanceOf(PolicyRegisterStateException.class).hasMessage(refusal);
    }

    @Test
    void aRejectedElectionNeverComesIntoForce() {
        as(UUID.randomUUID());
        LocalDate today = LocalDate.now(CIVIL);
        PolicyElectionView proposed = api.proposePolicyElection(
            new PolicyElectionInput("OCI_OPTION", "*", "ON", today, "Mismatch with assets at FVOCI"), "alice");
        PolicyElectionView rejected = api.rejectPolicyElection(proposed.electionId(), "Assets stay at FVTPL", "bob");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.decisionReason()).isEqualTo("Assets stay at FVTPL");
        assertThat(rejected.registerVersion()).isNull();
        assertThat(api.policyElectionInForce("OCI_OPTION", "*", today)).get()
            .extracting(PolicyElectionView::value).isEqualTo("OFF");
        assertThatThrownBy(() -> api.approvePolicyElection(proposed.electionId(), "AC-1", "carol"))
            .isInstanceOf(PolicyRegisterStateException.class);
    }

    private UUID tenantWithChart() {
        UUID tenant = UUID.randomUUID();
        as(tenant);
        seeder.seedIfAbsent(tenant, "system:test");
        return tenant;
    }

    private static void as(UUID tenant) {
        TenantContext.set(tenant);
    }

    /** One balanced EVENT journal, DR {@code debit} / CR {@code credit}; returns its id. */
    private UUID post(UUID tenant, String ref, String period, String debit, String credit, String amount) {
        return post(tenant, ref, period, debit, credit, amount, JournalSource.EVENT);
    }

    private UUID post(UUID tenant, String ref, String period, String debit, String credit, String amount,
                      JournalSource source) {
        JournalEntry entry = new JournalEntry(tenant, "billing.PremiumCollected", ref, period, "POL-PRD-1", "system:test")
            .withSource(source);
        entry.addLeg(debit, PostingDirection.DR, new BigDecimal(amount), "TZS");
        entry.addLeg(credit, PostingDirection.CR, new BigDecimal(amount), "TZS");
        UUID id = impl.postEntry(entry).orElseThrow().getJournalEntryId();
        TenantContext.set(tenant);
        return id;
    }
}
