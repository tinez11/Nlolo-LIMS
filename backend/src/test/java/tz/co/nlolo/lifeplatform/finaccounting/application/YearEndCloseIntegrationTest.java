package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.YearEndCloseNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.YearEndCloseStateException;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IFRS 17 I6, the year-end close against real Postgres as app_role (RLS): the year's classes 4-8 cleared to 3310 and on
 * to retained earnings with dividends, two people, December's lock waiting on it, a stale close replaced, and close
 * journals kept out of the expense pool. One class, one context.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class YearEndCloseIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "year_end_close_password";

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
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
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

    /** The document store is MinIO, which nothing here uses; the context needs the bean. */
    @MockBean
    private DocumentApi documents;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Autowired private YearEndCloses closes;
    @Autowired private ExpenseAllocations allocations;
    @Autowired private FinaccountingApiImpl ledgerApi;
    @Autowired private ChartOfAccountSeeder seeder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private static final int YEAR = 2025;

    /** One balanced SYSTEM journal in {@code period}: Dr {@code dr} / Cr {@code cr}. */
    private void post(UUID tenant, String period, String ref, String dr, String cr, String amount) {
        TenantContext.set(tenant);
        seeder.seedIfAbsent(tenant, "test");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            JournalEntry entry = new JournalEntry(tenant, "test.Posting", ref, period, null, "test")
                .withSource(JournalSource.SYSTEM);
            entry.addLeg(dr, PostingDirection.DR, new BigDecimal(amount), "TZS");
            entry.addLeg(cr, PostingDirection.CR, new BigDecimal(amount), "TZS");
            ledgerApi.postEntry(entry);
        });
    }

    private BigDecimal net(UUID tenant, String account) {
        TenantContext.set(tenant);
        return jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN direction = 'DR' THEN amount ELSE -amount END), 0)"
            + " FROM finaccounting.gl_posting WHERE tenant_id = ? AND account_code = ?", BigDecimal.class, tenant, account);
    }

    /**
     * A year: premium earned (4110 Cr 1,000) in March, a claim (5110 Dr 300) in June, payroll (8110 Dr 100) and a
     * dividend declared (3320 Dr 50) in December. March and June locked, December closing.
     */
    private UUID aYear() {
        UUID tenant = UUID.randomUUID();
        post(tenant, "2025-03", "rev", "2121", "4110", "1000.00");
        post(tenant, "2025-06", "clm", "5110", "2210", "300.00");
        post(tenant, "2025-12", "pay", "8110", "1110", "100.00");
        post(tenant, "2025-12", "div", "3320", "2740", "50.00");
        TenantContext.set(tenant);
        ledgerApi.startClosing("2025-03", "f");
        ledgerApi.lockPeriod("2025-03", "f");
        ledgerApi.startClosing("2025-06", "f");
        ledgerApi.lockPeriod("2025-06", "f");
        ledgerApi.startClosing("2025-12", "f");
        return tenant;
    }

    @Test
    void closedByASecondPersonItClearsTheYearIntoRetainedEarnings() {
        UUID tenant = aYear();
        var preview = closes.preview(YEAR);
        assertThat(preview.profit()).isEqualByComparingTo("600.00");
        assertThat(preview.dividends()).isEqualByComparingTo("50.00");
        assertThat(preview.accounts()).extracting(a -> a.code() + " " + a.balance())
            .containsExactly("4110 -1000.00", "5110 300.00", "8110 100.00");

        var prepared = closes.prepare(YEAR, "finance-one");
        assertThat(prepared.status()).isEqualTo("PREPARED");
        assertThatThrownBy(() -> closes.approve(prepared.closeId(), "finance-one"))
            .isInstanceOf(YearEndCloseStateException.class).hasMessageContaining("You prepared");
        var posted = closes.approve(prepared.closeId(), "finance-approver");
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.profit()).isEqualByComparingTo("600.00");
        assertThat(posted.classTotals()).containsKeys("4", "5", "8");
        assertThat(posted.stale()).isFalse();

        assertThat(net(tenant, "4110")).isZero();
        assertThat(net(tenant, "5110")).isZero();
        assertThat(net(tenant, "8110")).isZero();
        assertThat(net(tenant, "3310")).isZero();
        assertThat(net(tenant, "3320")).isZero();
        assertThat(net(tenant, "3210")).as("600 profit less 50 dividends, a credit").isEqualByComparingTo("-550.00");
        TenantContext.set(tenant);
        ledgerApi.lockPeriod("2025-12", "f");
        assertThat(ledgerApi.period("2025-12").status()).isEqualTo(PeriodStatus.LOCKED);
    }

    @Test
    void decemberDoesNotLockWithoutTheClose() {
        UUID tenant = aYear();
        TenantContext.set(tenant);
        assertThatThrownBy(() -> ledgerApi.lockPeriod("2025-12", "f")).hasMessageContaining("Year-end close required");
    }

    @Test
    void preparationNeedsDecemberClosingAndTheEarlierMonthsLocked() {
        UUID tenant = UUID.randomUUID();
        post(tenant, "2025-05", "rev", "2121", "4110", "10.00");
        TenantContext.set(tenant);
        assertThatThrownBy(() -> closes.prepare(YEAR, "f")).isInstanceOf(YearEndCloseStateException.class)
            .hasMessageContaining("is OPEN");
        ledgerApi.startClosing("2025-12", "f");
        assertThatThrownBy(() -> closes.prepare(YEAR, "f")).isInstanceOf(YearEndCloseStateException.class)
            .hasMessageContaining("Period 2025-05 must be locked first");
    }

    @Test
    void aLaterPostingMakesTheCloseStaleAndAReplacementReversesIt() {
        UUID tenant = aYear();
        var first = closes.approve(closes.prepare(YEAR, "f").closeId(), "a");
        post(tenant, "2025-12", "late", "8110", "1110", "20.00");
        TenantContext.set(tenant);
        assertThat(closes.get(first.closeId()).stale()).isTrue();
        assertThatThrownBy(() -> ledgerApi.lockPeriod("2025-12", "f")).hasMessageContaining("stale");

        var second = closes.approve(closes.prepare(YEAR, "f").closeId(), "a");
        assertThat(second.replacesId()).isEqualTo(first.closeId());
        assertThat(second.reversalJournalId()).isNotNull();
        assertThat(closes.get(first.closeId()).status()).isEqualTo("REPLACED");
        assertThat(net(tenant, "8110")).isZero();
        assertThat(net(tenant, "3210")).isEqualByComparingTo("-530.00");
        TenantContext.set(tenant);
        ledgerApi.lockPeriod("2025-12", "f");
    }

    @Test
    void closeJournalsAreNotInTheExpensePool() {
        UUID tenant = aYear();
        closes.approve(closes.prepare(YEAR, "f").closeId(), "a");
        TenantContext.set(tenant);
        assertThat(allocations.preview("2025-12", BigDecimal.ONE, null, null).pool()).isEqualByComparingTo("100.00");
    }

    @Test
    void anEmptyYearNeedsNoCloseAndCannotPrepareOne() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        ledgerApi.startClosing("2025-12", "f");
        assertThatThrownBy(() -> closes.prepare(YEAR, "f")).hasMessageContaining("Nothing to close in 2025");
        ledgerApi.lockPeriod("2025-12", "f");
        assertThatThrownBy(() -> closes.get(UUID.randomUUID())).isInstanceOf(YearEndCloseNotFoundException.class);
    }
}
