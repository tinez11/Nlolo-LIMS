package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IFRS 17 I5a, the engine period cycle against real Postgres as app_role (RLS): reinsurance groups and the policy
 * snapshot the extract reads, the extract, engine runs through 9160, and the reconciliation. One class, one context.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class EngineCycleIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "engine_ri_groups_password";

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

    /** The document store is MinIO; what is under test is what is stored, not where (the document module's own tests). */
    @org.springframework.boot.test.mock.mockito.MockBean
    private tz.co.nlolo.lifeplatform.document.api.DocumentApi documents;

    @org.junit.jupiter.api.BeforeEach
    void storeDocumentsAnywhere() {
        org.mockito.Mockito.when(documents.upload(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(call -> "doc-" + UUID.randomUUID());
    }

    @Autowired private EngineReinsuranceGroups groups;
    @Autowired private EnginePolicySnapshots snapshots;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private void publish(UUID tenantId, String type, Map<String, Object> payload) {
        TenantContext.set(tenantId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            events.publishEvent(DomainEventEnvelope.of(type, tenantId, payload)));
    }

    private static Map<String, String> money(String amount) {
        return Map.of("amount", amount, "currencyCode", "TZS");
    }

    // ---- fixtures for the extract and the runs ----

    @Autowired private FinaccountingApiImpl ledgerApi;
    @Autowired private tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder seeder;
    @Autowired private EngineExtracts extracts;

    private static final String PERIOD = "2026-08";
    private static final String GROUP = "TERM-GMM-2026-REM";
    private static final String RI_GROUP = "RI-AB12CD34-2026";

    /** A policy classified into {@code GROUP} (GMM), as I2's classification at sale would leave it. */
    private static void classify(UUID tenant, String policyNumber) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            UUID group = UUID.randomUUID();
            s.execute("INSERT INTO finaccounting.group_of_contracts (group_id, tenant_id, cohort_year, measurement_model,"
                + " group_key, portfolio_code, profitability_bucket) VALUES ('" + group + "', '" + tenant + "', 2026, 'GMM', '"
                + GROUP + "', 'TERM', 'REMAINING')");
            s.execute("INSERT INTO finaccounting.policy_classification (tenant_id, policy_number, reason, effective_from,"
                + " group_id, group_key, measurement_model, model_basis, register_version, portfolio_code, cohort_year,"
                + " profitability_bucket) VALUES ('" + tenant + "', '" + policyNumber + "', 'ISSUE', '2026-03-01', '" + group
                + "', '" + GROUP + "', 'GMM', 'REGISTER', 1, 'TERM', 2026, 'REMAINING')");
        }
    }

    /** One balanced EVENT journal: Dr {@code dr} / Cr {@code cr}, both lines carrying the dimensions given. */
    private void post(UUID tenant, String ref, String dr, String cr, String amount,
                      tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions dims, String policy) {
        TenantContext.set(tenant);
        seeder.seedIfAbsent(tenant, "test");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var entry = new tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry(tenant, "test.Posting", ref, PERIOD,
                policy, "test");
            entry.addLeg(dr, tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection.DR, new java.math.BigDecimal(amount),
                "TZS", dims);
            entry.addLeg(cr, tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection.CR, new java.math.BigDecimal(amount),
                "TZS", dims);
            ledgerApi.postEntry(entry);
        });
    }

    private static tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions policyLine(String movement) {
        return new tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions(GROUP, "GMM", movement, null, "TERM", null,
            null, null, "INVOICE", null);
    }

    private static tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions reinsuranceLine(String bordereauId) {
        return new tz.co.nlolo.lifeplatform.finaccounting.api.LineDimensions(null, null, null, null, null, null, null, null,
            "BORDEREAU", bordereauId);
    }

    /** A month with a policy group's premium and a treaty's bordereau, mapped to its reinsurance group. */
    private UUID monthWithAPolicyAndATreaty() throws Exception {
        UUID tenant = UUID.randomUUID();
        classify(tenant, "POL-EXT-1");
        publish(tenant, "policy.PolicyActivated", Map.of("policyNumber", "POL-EXT-1", "issueDate", "2026-03-01",
            "premiumFrequency", "MONTHLY", "sumAssured", money("2000000.00"), "premium", money("100000.00")));
        post(tenant, "inv-1", "2122", "2121", "100000.00", policyLine("PRM_REN"), "POL-EXT-1");
        String bordereau = UUID.randomUUID().toString();
        post(tenant, "bdx-1", "1436", "1430", "50000.00", reinsuranceLine(bordereau), null);
        TenantContext.set(tenant);
        groups.record(tenant, "BORDEREAU", bordereau, RI_GROUP);
        return tenant;
    }

    @Autowired private ExpenseAllocations allocations;

    /** Step 5 before step 6 (IFRS 17 I5b): none this month, approved by a second person. */
    private void noAllocation(UUID tenant) {
        TenantContext.set(tenant);
        var nil = allocations.prepare(PERIOD, new tz.co.nlolo.lifeplatform.finaccounting.api.ExpenseAllocationInput(
            java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, null, null,
            "No attributable spend in the test month"), "finance-one");
        allocations.approve(nil.allocationId(), false, "finance-approver");
    }

    @Test
    void anExtractIsRefusedUntilThePeriodIsClosing() throws Exception {
        UUID tenant = monthWithAPolicyAndATreaty();
        TenantContext.set(tenant);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> extracts.create(PERIOD, "finance-one"))
            .isInstanceOf(tz.co.nlolo.lifeplatform.finaccounting.api.EngineStateException.class)
            .hasMessageContaining("is OPEN");
    }

    /** Step 6: the period's actuals by group (the reinsurance bordereau in its RI group), balances, in-force policies. */
    @Test
    void theExtractCarriesEveryGroupsCashFlowsBalancesAndPolicies() throws Exception {
        UUID tenant = monthWithAPolicyAndATreaty();
        TenantContext.set(tenant);
        ledgerApi.startClosing(PERIOD, "finance-one");
        noAllocation(tenant);
        var first = extracts.create(PERIOD, "finance-one");
        assertThat(first.number()).isEqualTo(1);
        assertThat(first.groups()).containsExactly(RI_GROUP, GROUP);
        assertThat(first.policyRows()).isEqualTo(1);

        String cash = new String(extracts.render(first.extractId(), "csv", "cash-flows"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(cash).contains(GROUP + ",PRM_REN,2121,CR,100000.00,TZS")
            .contains(GROUP + ",PRM_REN,2122,DR,100000.00,TZS")
            .contains(RI_GROUP + ",,1436,DR,50000.00,TZS")
            .contains(RI_GROUP + ",,1430,CR,50000.00,TZS");
        String balances = new String(extracts.render(first.extractId(), "csv", "balances"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(balances).contains(GROUP + ",2121,0.00,-100000.00,TZS");
        String policies = new String(extracts.render(first.extractId(), "csv", "policies"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(policies).contains("POL-EXT-1," + GROUP + ",GMM,2026-03-01,2000000.00,100000.00,MONTHLY,ACTIVE");

        assertThat(extracts.create(PERIOD, "finance-one").number()).as("numbered per period").isEqualTo(2);
        TenantContext.set(UUID.randomUUID());
        assertThat(extracts.list(PERIOD)).as("another tenant").isEmpty();
    }

    // ---- engine runs (step 7) ----

    @Autowired private EngineRuns runs;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** A results workbook as the actuary fills the template: Header, Journal, Closing. */
    private static byte[] results(String reference, List<Object[]> journal, List<Object[]> closing) throws Exception {
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            fill(wb.createSheet("Header"), List.of(new Object[] {"Period", PERIOD}, new Object[] {"Extract", 1},
                new Object[] {"Engine reference", reference}, new Object[] {"Engine", "Prophet 9"},
                new Object[] {"Measurement date", "2026-08-31"}));
            List<Object[]> j = new java.util.ArrayList<>();
            j.add(new Object[] {"group", "entry", "account", "side", "amount", "movement", "note"});
            j.addAll(journal);
            fill(wb.createSheet("Journal"), j);
            List<Object[]> c = new java.util.ArrayList<>();
            c.add(new Object[] {"group", "lrc", "lic", "csm", "arc", "aic", "ri_csm"});
            c.addAll(closing);
            fill(wb.createSheet("Closing"), c);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void fill(org.apache.poi.ss.usermodel.Sheet sheet, List<Object[]> rows) {
        for (int r = 0; r < rows.size(); r++) {
            var row = sheet.createRow(r);
            for (int c = 0; c < rows.get(r).length; c++) {
                Object v = rows.get(r)[c];
                if (v instanceof Number n) {
                    row.createCell(c).setCellValue(n.doubleValue());
                } else if (v != null) {
                    row.createCell(c).setCellValue(v.toString());
                }
            }
        }
    }

    /** P-08 releases CSM for the policy group; P-16 takes the treaty's ceded premium into its asset. */
    private static final List<Object[]> RUN_LINES = List.of(
        new Object[] {GROUP, "P-08", "2112", "DR", "14000000.00", "CSM_REL", null},
        new Object[] {GROUP, "P-08", "4130", "CR", "14000000.00", "CSM_REL", null},
        new Object[] {RI_GROUP, "P-16", "1410", "DR", "50000.00", null, null},
        new Object[] {RI_GROUP, "P-16", "1436", "CR", "50000.00", null, null});

    /**
     * The ledger after RUN_LINES, net Dr - Cr: the policy group's 21xx is the premium's 2122/2121 (nets to 0) plus the
     * P-08 debit on 2112 -- LRC 14m, CSM 14m, LIC 0; the treaty's 1410 holds the 50,000 -- ARC 50,000.
     */
    private static List<Object[]> closing(String csm) {
        return List.of(new Object[] {GROUP, "14000000.00", "0", csm, null, null, null},
            new Object[] {RI_GROUP, null, null, null, "50000.00", "0", "0"});
    }

    private UUID closingMonthWithItsExtract() throws Exception {
        UUID tenant = monthWithAPolicyAndATreaty();
        TenantContext.set(tenant);
        ledgerApi.startClosing(PERIOD, "finance-one");
        noAllocation(tenant);
        extracts.create(PERIOD, "finance-one");
        return tenant;
    }

    private java.math.BigDecimal net(UUID tenant, String account) {
        TenantContext.set(tenant);
        return jdbc.queryForObject("SELECT COALESCE(sum(CASE WHEN direction = 'DR' THEN amount ELSE -amount END), 0)"
            + " FROM finaccounting.gl_posting WHERE tenant_id = ? AND account_code = ?", java.math.BigDecimal.class, tenant, account);
    }

    /** Step 7 end to end: validated, approved by a second person with the actuary's sign-off, posted through 9160. */
    @Test
    void aRunIsApprovedByASecondPersonAndPostedThrough9160AndAgreesWithTheLedger() throws Exception {
        UUID tenant = closingMonthWithItsExtract();
        var run = runs.upload(results("RUN-A", RUN_LINES, closing("14000000.00")), "run-a.xlsx", "finance-one");
        assertThat(run.status()).as(String.join("; ", run.errors())).isEqualTo("VALIDATED");
        assertThat(run.groups()).extracting(g -> g.group() + " " + g.reinsurance()).containsExactly(RI_GROUP + " true", GROUP + " false");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runs.approve(run.runId(), "AS-2026-08", "doc-report", "finance-one"))
            .isInstanceOf(tz.co.nlolo.lifeplatform.finaccounting.api.EngineStateException.class).hasMessageContaining("You uploaded");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runs.approve(run.runId(), " ", "doc-report", "finance-approver"))
            .isInstanceOf(tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException.class)
            .hasMessageContaining("sign-off reference");

        var posted = runs.approve(run.runId(), "AS-2026-08 (appointed actuary)", "doc-report", "finance-approver");
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.signOffReference()).isEqualTo("AS-2026-08 (appointed actuary)");
        assertThat(net(tenant, "9160")).as("9160 back at zero").isZero();
        assertThat(net(tenant, "4130")).isEqualByComparingTo("-14000000.00");
        TenantContext.set(tenant);
        assertThat(jdbc.queryForList("SELECT DISTINCT j.source_type || ' ' || p.ifrs17_group FROM finaccounting.journal_entry j"
                + " JOIN finaccounting.gl_posting p ON p.journal_entry_id = j.journal_entry_id WHERE j.engine_run_id = ?",
            String.class, run.runId())).containsExactlyInAnyOrder("ENGINE_RUN " + GROUP, "ENGINE_RUN " + RI_GROUP);
        assertThat(posted.reconciliation())
            .extracting(r -> r.group() + " " + r.figure() + " " + r.status())
            .containsExactlyInAnyOrder(GROUP + " CSM AGREED", GROUP + " LIC AGREED", GROUP + " LRC AGREED",
                RI_GROUP + " AIC AGREED", RI_GROUP + " ARC AGREED", RI_GROUP + " RI_CSM AGREED");

        TenantContext.set(tenant);
        assertThat(ledgerApi.lockPeriod(PERIOD, "finance-one").status())
            .isEqualTo(tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus.LOCKED);
    }

    @Test
    void anUploadThatFailsValidationIsKeptRejectedWithEveryError() throws Exception {
        closingMonthWithItsExtract();
        var run = runs.upload(results("RUN-BAD", List.of(
                new Object[] {"NOT-A-GROUP", "P-08", "2112", "DR", "5.00", null, null},
                new Object[] {"NOT-A-GROUP", "P-08", "4130", "CR", "4.00", null, null}),
            closing("0")), "bad.xlsx", "finance-one");
        assertThat(run.status()).isEqualTo("REJECTED");
        assertThat(run.errors()).anyMatch(e -> e.contains("group NOT-A-GROUP is not in extract 2026-08 #1"))
            .anyMatch(e -> e.contains("does not balance"));
        assertThat(run.groups()).as("nothing of a rejected file is kept to post").isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runs.approve(run.runId(), "AS", "doc", "finance-approver"))
            .isInstanceOf(tz.co.nlolo.lifeplatform.finaccounting.api.EngineStateException.class);
    }

    /**
     * A replacement reverses the posted run in full before posting itself; its CSM 5.00 off the ledger is an exception
     * that keeps the period from locking until one person explains it and another accepts it.
     */
    @Test
    void aReplacementReversesThePostedRunAndAnUnexplainedDifferenceBlocksTheLock() throws Exception {
        UUID tenant = closingMonthWithItsExtract();
        var first = runs.upload(results("RUN-1", RUN_LINES, closing("14000000.00")), "run-1.xlsx", "finance-one");
        runs.approve(first.runId(), "AS-1", "doc-1", "finance-approver");
        var second = runs.upload(results("RUN-2", RUN_LINES, closing("13999995.00")), "run-2.xlsx", "finance-one");
        assertThat(second.status()).isEqualTo("VALIDATED");
        var posted = runs.approve(second.runId(), "AS-2", "doc-2", "finance-approver");

        assertThat(posted.replacesRunId()).isEqualTo(first.runId());
        assertThat(runs.get(first.runId()).status()).isEqualTo("REPLACED");
        assertThat(runs.get(first.runId()).replacedByRunId()).isEqualTo(second.runId());
        assertThat(net(tenant, "4130")).as("the first run reversed in full, the second posted once").isEqualByComparingTo("-14000000.00");
        assertThat(net(tenant, "9160")).isZero();
        assertThat(posted.reconciliation()).filteredOn(r -> r.figure().equals("CSM")).singleElement()
            .satisfies(r -> {
                assertThat(r.status()).isEqualTo("EXCEPTION");
                assertThat(r.difference()).isEqualByComparingTo("5.00");
            });

        TenantContext.set(tenant);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledgerApi.lockPeriod(PERIOD, "finance-one"))
            .hasMessageContaining("Engine run RUN-2: " + GROUP + " CSM differs from the ledger by 5.00 TZS");
        runs.explain(second.runId(), GROUP, "CSM", "A late premium reversal the engine did not see; agreed with the actuary",
            "finance-one");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runs.accept(second.runId(), GROUP, "CSM", "finance-one"))
            .hasMessageContaining("You explained this difference");
        TenantContext.set(tenant);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledgerApi.lockPeriod(PERIOD, "finance-one"))
            .hasMessageContaining("(explained)");
        runs.accept(second.runId(), GROUP, "CSM", "finance-approver");
        TenantContext.set(tenant);
        assertThat(ledgerApi.lockPeriod(PERIOD, "finance-one").status())
            .isEqualTo(tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus.LOCKED);
    }

    /** The policies sheet's facts, kept from policy's events: activated with its terms, then out of force on lapse. */
    @Test
    void aPolicysSnapshotFollowsItsLifecycle() {
        UUID tenant = UUID.randomUUID();
        publish(tenant, "policy.PolicyActivated", Map.of("policyNumber", "POL-SNAP-1", "issueDate", "2026-03-01",
            "premiumFrequency", "MONTHLY", "sumAssured", money("2000000.00"), "premium", money("100000.00")));
        TenantContext.set(tenant);
        assertThat(snapshots.inForce(tenant)).singleElement().satisfies(s -> {
            assertThat(s.policyNumber()).isEqualTo("POL-SNAP-1");
            assertThat(s.sumAssured()).isEqualByComparingTo("2000000.00");
            assertThat(s.premium()).isEqualByComparingTo("100000.00");
            assertThat(s.frequency()).isEqualTo("MONTHLY");
            assertThat(s.currency()).isEqualTo("TZS");
            assertThat(s.issueDate()).isEqualTo(java.time.LocalDate.of(2026, 3, 1));
        });

        publish(tenant, "policy.PolicyLapsed", Map.of("policyNumber", "POL-SNAP-1", "lapsedAt", "2026-06-01T00:00:00Z"));
        TenantContext.set(tenant);
        assertThat(snapshots.inForce(tenant)).isEmpty();
        publish(tenant, "policy.PolicyReinstated", Map.of("policyNumber", "POL-SNAP-1", "reinstatedAt", "2026-07-01T00:00:00Z"));
        TenantContext.set(tenant);
        assertThat(snapshots.inForce(tenant)).extracting(EnginePolicySnapshots.Snapshot::status).containsExactly("REINSTATED");
    }

    @Test
    void aBordereauARecoveryAndAStatementAreMappedToTheirTreatysGroup() {
        UUID tenant = UUID.randomUUID();
        UUID bordereau = UUID.randomUUID();
        UUID recovery = UUID.randomUUID();
        UUID statement = UUID.randomUUID();
        publish(tenant, "reinsurance.BordereauPosted", Map.of("bordereauId", bordereau.toString(), "treatyId",
            UUID.randomUUID().toString(), "reinsuranceGroup", "RI-AB12CD34-2026", "period", "2026-09",
            "premium", money("400.00"), "commission", money("0.00")));
        publish(tenant, "reinsurance.RecoveryCalculated", Map.of("recoveryId", recovery, "claimId", UUID.randomUUID(),
            "policyNumber", "POL-RI-1", "treatyId", UUID.randomUUID(), "reinsuranceGroup", "RI-AB12CD34-2026",
            "recoverableAmount", money("1000.00")));
        publish(tenant, "reinsurance.StatementApproved", Map.of("statementId", statement.toString(), "treatyId",
            UUID.randomUUID().toString(), "reinsuranceGroup", "RI-AB12CD34-2026", "quarter", "2026-Q3",
            "premium", money("400.00")));

        TenantContext.set(tenant);
        assertThat(groups.groupOf(tenant, "BORDEREAU", bordereau.toString())).contains("RI-AB12CD34-2026");
        assertThat(groups.groupOf(tenant, "RECOVERY", recovery.toString())).contains("RI-AB12CD34-2026");
        assertThat(groups.groupOf(tenant, "STATEMENT", statement.toString())).contains("RI-AB12CD34-2026");

        // Redelivered: still one row, nothing thrown.
        publish(tenant, "reinsurance.StatementApproved", Map.of("statementId", statement.toString(),
            "reinsuranceGroup", "RI-AB12CD34-2026"));
        TenantContext.set(UUID.randomUUID());
        assertThat(groups.groupOf(TenantContext.get(), "BORDEREAU", bordereau.toString())).as("another tenant").isEmpty();
    }

    /** IFRS 17 I5b: once a later extract exists, results answering an older one are rejected with the reason. */
    @Test
    void resultsMustAnswerTheLatestExtract() throws Exception {
        UUID tenant = closingMonthWithItsExtract();
        TenantContext.set(tenant);
        extracts.create(PERIOD, "finance-one");   // #2
        var run = runs.upload(results("RUN-OLD-EXTRACT", RUN_LINES, closing("14000000.00")), "r.xlsx", "finance-one");
        assertThat(run.status()).isEqualTo("REJECTED");
        assertThat(run.errors()).contains("Header: extract #1 is not the latest (#2); results answer the latest extract");
    }
}
