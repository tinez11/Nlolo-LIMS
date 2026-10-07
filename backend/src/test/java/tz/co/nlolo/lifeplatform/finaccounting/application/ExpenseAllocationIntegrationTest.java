package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.EngineStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * IFRS 17 I5b, P-19's expense allocation against real Postgres as app_role (RLS): the drivers and the pool read from the
 * ledger and the classifications, two-person approval posting one SYSTEM journal, replacement, and the gates it puts on
 * the extract and the lock. One class, one context.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ExpenseAllocationIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "expense_allocation_password";

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
            "db-migrations/finaccounting/V16__expense_allocation.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    /** The document store is MinIO; the extract stores its workbook there, which is not what is under test. */
    @MockBean
    private DocumentApi documents;

    @BeforeEach
    void storeDocumentsAnywhere() {
        when(documents.upload(anyString(), any(), anyString(), any(), anyLong(), anyString(), anyString()))
            .thenAnswer(call -> "doc-" + UUID.randomUUID());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Autowired private ExpenseAllocations allocations;
    @Autowired private EngineExtracts extracts;
    @Autowired private FinaccountingApiImpl ledgerApi;
    @Autowired private ChartOfAccountSeeder seeder;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private static final String PERIOD = "2026-08";
    private static final String TERM = "TERM-GMM-2026-REM";
    private static final String FUN = "FUN-PAA-2026-REM";

    /** A group (created once) and a policy classified into it, issued on {@code issued}. */
    private static void classify(UUID tenant, String group, String model, String policyNumber, String issued)
            throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO finaccounting.group_of_contracts (group_id, tenant_id, cohort_year, measurement_model,"
                + " group_key, portfolio_code, profitability_bucket) SELECT gen_random_uuid(), '" + tenant + "', 2026, '"
                + model + "', '" + group + "', 'TERM', 'REMAINING' WHERE NOT EXISTS (SELECT 1 FROM"
                + " finaccounting.group_of_contracts WHERE tenant_id = '" + tenant + "' AND group_key = '" + group + "')");
            s.execute("INSERT INTO finaccounting.policy_classification (tenant_id, policy_number, reason, effective_from,"
                + " group_id, group_key, measurement_model, model_basis, register_version, portfolio_code, cohort_year,"
                + " profitability_bucket) SELECT '" + tenant + "', '" + policyNumber + "', 'ISSUE', '" + issued
                + "', group_id, group_key, '" + model + "', 'REGISTER', 1, 'TERM', 2026, 'REMAINING'"
                + " FROM finaccounting.group_of_contracts WHERE tenant_id = '" + tenant + "' AND group_key = '" + group + "'");
        }
    }

    private void activate(UUID tenant, String policyNumber) {
        TenantContext.set(tenant);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            events.publishEvent(DomainEventEnvelope.of("policy.PolicyActivated", tenant, Map.of("policyNumber",
                policyNumber, "issueDate", "2026-03-01", "premiumFrequency", "MONTHLY",
                "sumAssured", Map.of("amount", "1000000.00", "currencyCode", "TZS"),
                "premium", Map.of("amount", "10000.00", "currencyCode", "TZS")))));
    }

    /** One balanced SYSTEM journal: Dr {@code dr} / Cr {@code cr}. */
    private void post(UUID tenant, String ref, String dr, String cr, String amount, LineDimensions dims) {
        TenantContext.set(tenant);
        seeder.seedIfAbsent(tenant, "test");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            JournalEntry entry = new JournalEntry(tenant, "test.Posting", ref, PERIOD, null, "test")
                .withSource(JournalSource.SYSTEM);
            entry.addLeg(dr, PostingDirection.DR, new BigDecimal(amount), "TZS", dims);
            entry.addLeg(cr, PostingDirection.CR, new BigDecimal(amount), "TZS", dims);
            ledgerApi.postEntry(entry);
        });
    }

    /**
     * A closing month: a GMM group with two policies in force and one claim notified in August, a PAA group with one
     * policy issued in August, an IFRS 9 group (ignored), and a 1,000 expense pool (salaries).
     */
    private UUID closingMonth() throws Exception {
        UUID tenant = UUID.randomUUID();
        classify(tenant, TERM, "GMM", "POL-A", "2026-03-01");
        classify(tenant, TERM, "GMM", "POL-B", "2026-03-01");
        classify(tenant, FUN, "PAA", "POL-C", "2026-08-10");
        classify(tenant, "SAV-IFRS9-2026-REM", "IFRS9", "POL-D", "2026-08-10");
        for (String p : List.of("POL-A", "POL-B", "POL-C", "POL-D")) {
            activate(tenant, p);
        }
        post(tenant, "claim-1", "5110", "2210", "500.00",
            new LineDimensions(TERM, "GMM", null, null, "TERM", null, null, null, "CLAIM", "CLM-1"));
        post(tenant, "payroll-1", "8110", "1110", "1000.00", LineDimensions.NONE);
        TenantContext.set(tenant);
        ledgerApi.startClosing(PERIOD, "finance-one");
        return tenant;
    }

    private static ExpenseAllocationInput totals(String m, String c, String a) {
        return new ExpenseAllocationInput(new BigDecimal(m), new BigDecimal(c), new BigDecimal(a), "Study 2026-Q3", null,
            null);
    }

    private static ExpenseAllocationInput none() {
        return new ExpenseAllocationInput(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null,
            "No attributable spend this month");
    }

    private List<String> postedLines(UUID tenant, UUID allocationId) {
        TenantContext.set(tenant);
        return jdbc.queryForList("SELECT p.account_code || ' ' || p.direction || ' ' || p.amount || ' '"
                + " || coalesce(p.ifrs17_group, '-') || ' ' || coalesce(p.movement_type, '-') || ' ' || j.source_type"
                + " FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j"
                + " ON j.journal_entry_id = p.journal_entry_id"
                + " WHERE j.expense_allocation_id = ? AND j.source_event = ? ORDER BY p.account_code, p.ifrs17_group",
            String.class, allocationId, ExpenseAllocations.EVENT);
    }

    @Test
    void preparedThenApprovedByASecondPersonItPostsP19ByDriver() throws Exception {
        UUID tenant = closingMonth();
        var preview = allocations.preview(PERIOD, new BigDecimal("300.00"), new BigDecimal("100.00"),
            new BigDecimal("60.00"));
        assertThat(preview.pool()).isEqualByComparingTo("1000.00");
        assertThat(preview.overPool()).isFalse();
        assertThat(preview.lines()).extracting(l -> l.group() + " " + l.category() + " " + l.account() + " " + l.amount())
            .containsExactly(FUN + " MAINTENANCE 5210 100.00", TERM + " MAINTENANCE 5210 200.00",
                TERM + " CLAIMS_HANDLING 5215 100.00", FUN + " ACQUISITION 5310 60.00");

        var prepared = allocations.prepare(PERIOD, totals("300.00", "100.00", "60.00"), "finance-one");
        assertThat(prepared.status()).isEqualTo("PREPARED");
        assertThat(prepared.lines()).hasSize(4);
        assertThatThrownBy(() -> allocations.approve(prepared.allocationId(), false, "finance-one"))
            .isInstanceOf(ExpenseAllocationStateException.class).hasMessageContaining("You prepared");
        var posted = allocations.approve(prepared.allocationId(), false, "finance-approver");
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.pool()).isEqualByComparingTo("1000.00");
        assertThat(posted.lines()).hasSize(4);

        assertThat(postedLines(tenant, prepared.allocationId()))
            .containsExactly("5210 DR 100.00 " + FUN + " - SYSTEM", "5210 DR 200.00 " + TERM + " - SYSTEM",
                "5215 DR 100.00 " + TERM + " - SYSTEM", "5310 DR 60.00 " + FUN + " - SYSTEM", "8490 CR 460.00 - - SYSTEM");
        assertThat(allocations.preview(PERIOD, BigDecimal.ONE, null, null).pool())
            .as("the allocation's own journal is not in the pool").isEqualByComparingTo("1000.00");
    }

    @Test
    void aGmmGroupsAcquisitionGoesTo2123TaggedExpAcq() throws Exception {
        UUID tenant = closingMonth();
        classify(tenant, "END-GMM-2026-REM", "GMM", "POL-E", "2026-08-20");
        var preview = allocations.preview(PERIOD, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.00"));
        assertThat(preview.lines()).extracting(l -> l.group() + " " + l.account() + " " + l.amount())
            .containsExactly("END-GMM-2026-REM 2123 5.00", FUN + " 5310 5.00");
        var prepared = allocations.prepare(PERIOD, totals("0", "0", "10.00"), "finance-one");
        allocations.approve(prepared.allocationId(), false, "finance-approver");
        assertThat(postedLines(tenant, prepared.allocationId()))
            .contains("2123 DR 5.00 END-GMM-2026-REM EXP_ACQ SYSTEM");
    }

    @Test
    void aboveThePoolTheApproverMustSaySo() throws Exception {
        closingMonth();
        var prepared = allocations.prepare(PERIOD, totals("1500.00", "0", "0"), "finance-one");
        assertThat(allocations.get(prepared.allocationId()).overPool()).isTrue();
        assertThatThrownBy(() -> allocations.approve(prepared.allocationId(), false, "finance-approver"))
            .isInstanceOf(ExpenseAllocationStateException.class).hasMessageContaining("above the month's pool");
        var posted = allocations.approve(prepared.allocationId(), true, "finance-approver");
        assertThat(posted.overPool()).isTrue();
        assertThat(posted.status()).isEqualTo("POSTED");
    }

    @Test
    void aNilAllocationNeedsAReasonAndIsApprovedLikeAnyOther() throws Exception {
        closingMonth();
        assertThatThrownBy(() -> allocations.prepare(PERIOD, new ExpenseAllocationInput(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, " "), "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("say why");
        assertThatThrownBy(() -> allocations.prepare(PERIOD, new ExpenseAllocationInput(new BigDecimal("1.005"), null,
                null, "Study", null, null), "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("two decimals");
        assertThatThrownBy(() -> allocations.prepare(PERIOD, new ExpenseAllocationInput(new BigDecimal("1.00"), null,
                null, null, null, null), "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("study");
        var nil = allocations.prepare(PERIOD, none(), "finance-one");
        var posted = allocations.approve(nil.allocationId(), false, "finance-approver");
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.journalEntryId()).isNull();
        assertThat(posted.lines()).isEmpty();
    }

    @Test
    void aReplacementReversesTheOldJournalAndOnlyOneStaysPosted() throws Exception {
        UUID tenant = closingMonth();
        var first = allocations.prepare(PERIOD, totals("300.00", "0", "0"), "finance-one");
        allocations.approve(first.allocationId(), false, "finance-approver");
        var second = allocations.prepare(PERIOD, totals("1.00", "0", "0"), "finance-one");
        assertThatThrownBy(() -> allocations.prepare(PERIOD, totals("2.00", "0", "0"), "finance-one"))
            .isInstanceOf(ExpenseAllocationStateException.class).hasMessageContaining("awaiting a decision");
        assertThat(allocations.list(PERIOD)).extracting(v -> v.allocationId()).first().isEqualTo(second.allocationId());
        var posted = allocations.approve(second.allocationId(), false, "finance-approver");
        assertThat(posted.replacesId()).isEqualTo(first.allocationId());
        assertThat(posted.reversalJournalId()).isNotNull();
        assertThat(allocations.get(first.allocationId()).status()).isEqualTo("REPLACED");
        TenantContext.set(tenant);
        assertThat(jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN direction = 'DR' THEN amount ELSE -amount END), 0)"
            + " FROM finaccounting.gl_posting WHERE tenant_id = ? AND account_code = '5210'", BigDecimal.class, tenant))
            .as("300 reversed, 1 posted").isEqualByComparingTo("1.00");
        assertThat(jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN direction = 'DR' THEN amount ELSE -amount END), 0)"
            + " FROM finaccounting.gl_posting WHERE tenant_id = ? AND account_code = '8490'", BigDecimal.class, tenant))
            .isEqualByComparingTo("-1.00");
    }

    @Test
    void refusedOutsideAClosingMonthAndAcrossTenants() throws Exception {
        UUID stranger = UUID.randomUUID();
        TenantContext.set(stranger);
        assertThatThrownBy(() -> allocations.prepare("2026-07", totals("1.00", "0", "0"), "finance-one"))
            .isInstanceOf(ExpenseAllocationStateException.class).hasMessageContaining("is OPEN");
        UUID tenant = closingMonth();
        var prepared = allocations.prepare(PERIOD, totals("1.00", "0", "0"), "finance-one");
        TenantContext.set(stranger);
        assertThatThrownBy(() -> allocations.get(prepared.allocationId()))
            .isInstanceOf(ExpenseAllocationNotFoundException.class);
        assertThatThrownBy(() -> allocations.reject(prepared.allocationId(), "no", "finance-approver"))
            .isInstanceOf(ExpenseAllocationNotFoundException.class);
        TenantContext.set(tenant);
        assertThat(allocations.reject(prepared.allocationId(), "Wrong study", "finance-approver").status())
            .isEqualTo("REJECTED");
    }
}
