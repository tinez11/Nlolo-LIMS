package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.ModelBasis;
import tz.co.nlolo.lifeplatform.finaccounting.api.PolicyClassificationView;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IFRS 17 I2: finaccounting classifies every contract from policy.PolicyIssued (and a vesting pension again from
 * policy.AnnuityVested), through the real AFTER_COMMIT listener, against the accounting policy register's baseline.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class PolicyClassificationIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "classification_it_password";

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
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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

    @Autowired private FinaccountingApi api;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void anIssuedTermPolicyJoinsItsPortfolioModelCohortAndBucketGroup() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-CLS-01", "TERM", "2026-03-10", null, "AGENT", "ARU");

        List<PolicyClassificationView> rows = read(tenant, "POL-CLS-01");
        assertThat(rows).hasSize(1);
        PolicyClassificationView c = rows.get(0);
        assertThat(c.groupKey()).isEqualTo("TERM-GMM-2026-REM");
        assertThat(c.measurementModel()).isEqualTo("GMM");
        assertThat(c.modelBasis()).isEqualTo(ModelBasis.REGISTER);
        assertThat(c.registerVersion()).isEqualTo(43);
        assertThat(c.salesChannel()).isEqualTo("AGENT");
        assertThat(c.branchCode()).isEqualTo("ARU");
    }

    @Test
    void anOverrideCountsOnlyWhereTheRegisterAllowsIt() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-CLS-CRL", "CRL", "2026-05-01", "PAA", "BANCASSURANCE", "DSM");
        issue(tenant, "POL-CLS-TRM", "TERM", "2026-05-01", "PAA", "DIRECT", "DSM");

        PolicyClassificationView lender = read(tenant, "POL-CLS-CRL").get(0);
        assertThat(lender.groupKey()).isEqualTo("CRL-PAA-2026-REM");
        assertThat(lender.modelBasis()).isEqualTo(ModelBasis.OVERRIDE);

        PolicyClassificationView refused = read(tenant, "POL-CLS-TRM").get(0);
        assertThat(refused.groupKey()).as("the register's model applies").isEqualTo("TERM-GMM-2026-REM");
        assertThat(refused.modelBasis()).isEqualTo(ModelBasis.OVERRIDE_REFUSED);
        assertThat(refused.requestedOverride()).isEqualTo("PAA");
    }

    @Test
    void aRedeliveryWritesNothingAndTwoPoliciesShareOneGroup() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-CLS-A", "END", "2026-07-01", null, "DIRECT", "DSM");
        issue(tenant, "POL-CLS-A", "END", "2026-07-01", null, "DIRECT", "DSM");
        issue(tenant, "POL-CLS-B", "END", "2026-09-01", null, "DIRECT", "DSM");

        assertThat(read(tenant, "POL-CLS-A")).hasSize(1);
        assertThat(countAsOwner("SELECT count(*) FROM finaccounting.group_of_contracts WHERE tenant_id = '" + tenant
            + "' AND group_key = 'END-GMM-2026-REM'")).isEqualTo(1);
    }

    @Test
    void aVestingPensionIsClassifiedAgainAsANewImmediateAnnuityContract() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-CLS-PEN", "PEN", "2026-02-01", null, "AGENT", "MWZ");
        publish(tenant, "policy.AnnuityVested", Map.of("policyNumber", "POL-CLS-PEN", "vestedOn", "2031-03-01"));

        List<PolicyClassificationView> rows = read(tenant, "POL-CLS-PEN");
        assertThat(rows).extracting(PolicyClassificationView::reason).containsExactly("ISSUE", "VESTING");
        assertThat(rows.get(0).groupKey()).isEqualTo("PEN-IFRS9-2026-REM");
        assertThat(rows.get(1).groupKey()).isEqualTo("IANN-GMM-2031-REM");
        assertThat(rows.get(1).branchCode()).isEqualTo("MWZ");

        // A contract not in IFRS 9 deferral keeps its classification at vesting.
        issue(tenant, "POL-CLS-IANN", "IANN", "2026-02-01", null, "AGENT", "MWZ");
        publish(tenant, "policy.AnnuityVested", Map.of("policyNumber", "POL-CLS-IANN", "vestedOn", "2031-03-01"));
        assertThat(read(tenant, "POL-CLS-IANN")).hasSize(1);
    }

    @Test
    void aClassificationIsNeverChangedAndAnEventFromBeforeI2IsLeftAlone() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-CLS-FIX", "WL", "2026-04-01", null, "DIRECT", "DSM");
        TenantContext.set(tenant);
        assertThatThrownBy(() -> jdbc.update("UPDATE finaccounting.policy_classification SET branch_code = 'ZNZ'"
            + " WHERE policy_number = 'POL-CLS-FIX'")).hasStackTraceContaining("CLASSIFICATION_IMMUTABLE");

        Map<String, Object> old = new HashMap<>();
        old.put("policyNumber", "POL-CLS-OLD");
        old.put("issueDate", "2025-01-01");
        publish(tenant, "policy.PolicyIssued", old);
        assertThat(read(tenant, "POL-CLS-OLD")).isEmpty();
    }

    @Test
    void theClassificationIsReadableOverTheApiForTheTenantOnly() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-CLS-RLS", "FUN", "2026-06-01", null, "AGENT", "DOD");
        assertThat(read(tenant, "POL-CLS-RLS")).extracting(PolicyClassificationView::groupKey)
            .containsExactly("FUN-PAA-2026-REM");
        assertThat(read(UUID.randomUUID(), "POL-CLS-RLS")).isEmpty();
    }

    private void issue(UUID tenant, String policyNumber, String portfolio, String issueDate, String override,
                       String channel, String branch) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("issueDate", issueDate);
        payload.put("productId", UUID.randomUUID());
        payload.put("productVersionId", UUID.randomUUID());
        payload.put("portfolioCode", portfolio);
        payload.put("cohortYear", Integer.parseInt(issueDate.substring(0, 4)));
        payload.put("profitabilityBucket", "REMAINING");
        payload.put("measurementModelOverride", override);
        payload.put("salesChannel", channel);
        payload.put("branchCode", branch);
        publish(tenant, "policy.PolicyIssued", payload);
    }

    /** Inside a committed transaction, so the AFTER_COMMIT listener fires exactly as it does behind policy. */
    private void publish(UUID tenant, String type, Map<String, Object> payload) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
            events.publishEvent(DomainEventEnvelope.of(type, tenant, payload)));
    }

    private List<PolicyClassificationView> read(UUID tenant, String policyNumber) {
        TenantContext.set(tenant);
        return api.policyClassifications(policyNumber);
    }

    private static long countAsOwner(String sql) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
