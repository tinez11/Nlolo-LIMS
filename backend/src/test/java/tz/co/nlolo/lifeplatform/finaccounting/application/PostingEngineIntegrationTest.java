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
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingQueueApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventResolvedException;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventView;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IFRS 17 I3a: an event reaches the ledger through the posting rules, by the contract's measurement model, with its
 * dimensions on every line; what the rules cannot post is queued, never dropped, and holds its period open until it is
 * posted or dismissed; PAA premium is earned month by month over the cover it pays for.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class PostingEngineIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "posting_engine_it_password";
    private static final String THIS_MONTH = YearMonth.now(ZoneId.of("Africa/Dar_es_Salaam")).toString();

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
    @Autowired private PostingQueueApi queue;
    @Autowired private PolicyClassifier classifier;
    @Autowired private PaaEarningJob earning;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PostingRules postingRules;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void anInvoicePostsByTheContractsModelWithItsDimensionsOnEveryLine() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-ENG-GMM", "TERM", null, "AGENT", "ARU");
        issue(tenant, "POL-ENG-PAA", "CRL", "PAA", "BANCASSURANCE", "DSM");
        UUID gmmInvoice = invoice(tenant, "POL-ENG-GMM", "1200.00", null);
        UUID paaInvoice = invoice(tenant, "POL-ENG-PAA", "36500.00", "|2026-01-01|2026-12-31|36500.00");

        List<Map<String, Object>> gmm = lines(tenant, gmmInvoice.toString());
        assertThat(gmm).extracting(l -> l.get("account_code") + " " + l.get("direction"))
            .containsExactlyInAnyOrder("2122 DR", "2121 CR");
        assertThat(gmm).allSatisfy(l -> {
            assertThat(l.get("ifrs17_group")).isEqualTo("TERM-GMM-2026-REM");
            assertThat(l.get("measurement_model")).isEqualTo("GMM");
            assertThat(l.get("movement_type")).isEqualTo("PRM_REN");
            assertThat(l.get("portfolio")).isEqualTo("TERM");
            assertThat(l.get("channel")).isEqualTo("AGENT");
            assertThat(l.get("branch")).isEqualTo("ARU");
            assertThat(l.get("product_id")).isNotNull();
            assertThat(l.get("reference_type")).isEqualTo("INVOICE");
            assertThat(l.get("reference")).isEqualTo(gmmInvoice.toString());
        });
        // The version the loaded rules file declares, not a literal: I3c moved the file to v3 and a literal "v2" here
        // went stale without anyone noticing.
        assertThat(journal(tenant, gmmInvoice.toString()).get("rule_version"))
            .isEqualTo(postingRules.ruleSet().versionLabel()).asString().startsWith("posting-rules v");

        assertThat(lines(tenant, paaInvoice.toString())).extracting(l -> l.get("account_code") + " " + l.get("direction"))
            .containsExactlyInAnyOrder("2142 DR", "2141 CR");
        assertThat(lines(tenant, paaInvoice.toString())).allSatisfy(l ->
            assertThat(l.get("ifrs17_group")).isEqualTo("CRL-PAA-2026-REM"));
        TenantContext.set(tenant);
        assertThat(jdbc.queryForObject("SELECT amount FROM finaccounting.paa_earning WHERE invoice_ref = ?",
            BigDecimal.class, paaInvoice.toString())).isEqualByComparingTo("36500.00");
    }

    @Test
    void aRedeliveredEventPostsNothingTwice() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-ENG-RED", "TERM", null, "DIRECT", "DSM");
        UUID invoiceId = UUID.randomUUID();
        Map<String, Object> payload = invoicePayload(invoiceId, "POL-ENG-RED", "500.00", null);
        publish(tenant, "billing.PremiumInvoiceGenerated", payload);
        publish(tenant, "billing.PremiumInvoiceGenerated", payload);
        assertThat(lines(tenant, invoiceId.toString())).hasSize(2);
    }

    @Test
    void anUnclassifiedPolicysEventIsQueuedThenPostsWhenThePolicyIsClassified() {
        UUID tenant = UUID.randomUUID();
        UUID invoiceId = invoice(tenant, "POL-ENG-LATE", "800.00", null);

        assertThat(lines(tenant, invoiceId.toString())).isEmpty();
        UnpostedEventView queued = open(tenant).get(0);
        assertThat(queued.reason()).isEqualTo("UNMAPPED");
        assertThat(queued.sourceRef()).isEqualTo(invoiceId.toString());
        assertThat(queued.detail()).contains("model UNCLASSIFIED").contains("no IFRS 17 classification");
        assertThat(queued.amounts().get("amount")).isEqualByComparingTo("800.00");

        issue(tenant, "POL-ENG-LATE", "TERM", null, "DIRECT", "DSM");

        assertThat(lines(tenant, invoiceId.toString())).hasSize(2);
        assertThat(open(tenant)).isEmpty();
        TenantContext.set(tenant);
        UnpostedEventView resolved = queue.unpostedEvents(false).get(0);
        assertThat(resolved.resolution()).isEqualTo("POSTED");
        assertThat(resolved.journalEntryId()).isEqualTo(journal(tenant, invoiceId.toString()).get("journal_entry_id"));
    }

    @Test
    void financeRetriesAQueuedEventOnceTheCauseIsFixed() {
        UUID tenant = UUID.randomUUID();
        UUID invoiceId = invoice(tenant, "POL-ENG-RETRY", "300.00", null);
        UnpostedEventView queued = open(tenant).get(0);

        // Classified without the listener, so nothing retries it on its own.
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            TenantContext.set(tenant);
            classifier.classify(new PolicyClassifier.Input(tenant, "POL-ENG-RETRY", "ISSUE", LocalDate.of(2026, 3, 1),
                "TERM", 2026, "REMAINING", null, UUID.randomUUID(), UUID.randomUUID(), "DIRECT", "DSM"));
        });
        assertThat(lines(tenant, invoiceId.toString())).isEmpty();

        TenantContext.set(tenant);
        UnpostedEventView retried = queue.retryUnpostedEvent(queued.id(), "finance-one");
        assertThat(retried.resolution()).isEqualTo("POSTED");
        assertThat(retried.resolvedBy()).isEqualTo("finance-one");
        assertThat(lines(tenant, invoiceId.toString())).hasSize(2);
        assertThat(journal(tenant, invoiceId.toString()).get("created_by")).isEqualTo("finance-one");

        TenantContext.set(tenant);
        assertThatThrownBy(() -> queue.retryUnpostedEvent(queued.id(), "finance-one"))
            .isInstanceOf(UnpostedEventResolvedException.class);
    }

    @Test
    void aRefusedJournalIsQueuedAndHoldsItsPeriodOpenUntilDismissedWithAReason() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-ENG-CLOSE", "TERM", null, "DIRECT", "DSM");
        TenantContext.set(tenant);
        api.startClosing(THIS_MONTH, "finance-one");

        UUID invoiceId = invoice(tenant, "POL-ENG-CLOSE", "700.00", null);
        assertThat(lines(tenant, invoiceId.toString())).isEmpty();
        UnpostedEventView refused = open(tenant).get(0);
        assertThat(refused.reason()).isEqualTo("REFUSED");
        assertThat(refused.detail()).contains("LEDGER_PERIOD_CLOSING");
        assertThat(refused.period()).isEqualTo(THIS_MONTH);

        TenantContext.set(tenant);
        assertThatThrownBy(() -> api.lockPeriod(THIS_MONTH, "finance-two"))
            .isInstanceOf(PeriodStateException.class)
            .hasMessage("1 event is not posted; post or dismiss it before the period locks");

        TenantContext.set(tenant);
        assertThatThrownBy(() -> queue.dismissUnpostedEvent(refused.id(), " ", "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class);
        UnpostedEventView dismissed = queue.dismissUnpostedEvent(refused.id(), "Billed in error; credited by hand",
            "finance-one");
        assertThat(dismissed.resolution()).isEqualTo("DISMISSED");
        assertThat(dismissed.resolutionReason()).isEqualTo("Billed in error; credited by hand");
        assertThatThrownBy(() -> queue.dismissUnpostedEvent(refused.id(), "again", "finance-one"))
            .isInstanceOf(UnpostedEventResolvedException.class);

        assertThat(api.lockPeriod(THIS_MONTH, "finance-two").status().name()).isEqualTo("LOCKED");
    }

    @Test
    void paaPremiumIsEarnedMonthByMonthOnceAndAWaiverStopsWhatIsLeft() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-ENG-EARN", "CRL", "PAA", "BANCASSURANCE", "DSM");
        UUID invoiceId = invoice(tenant, "POL-ENG-EARN", "36500.00", "|2026-01-01|2026-12-31|36500.00");

        earn(tenant, "POL-ENG-EARN", LocalDate.of(2026, 2, 28));
        assertThat(revenue(tenant)).isEqualByComparingTo("5900.00");   // 59 of 365 days
        Map<String, Object> january = journal(tenant, "POL-ENG-EARN:2026-01");
        assertThat(january.get("source_type")).isEqualTo("SYSTEM");
        assertThat(january.get("period")).isEqualTo("2026-01");
        assertThat(lines(tenant, "POL-ENG-EARN:2026-01")).extracting(l -> l.get("account_code") + " " + l.get("amount"))
            .containsExactlyInAnyOrder("2141 3100.00", "4160 3100.00");

        earn(tenant, "POL-ENG-EARN", LocalDate.of(2026, 2, 28));
        assertThat(revenue(tenant)).as("a re-run earns nothing").isEqualByComparingTo("5900.00");

        publish(tenant, "billing.InvoiceWaived", Map.of("invoiceId", invoiceId, "policyNumber", "POL-ENG-EARN",
            "amount", Map.of("amount", "36500.00", "currencyCode", "TZS")));
        earn(tenant, "POL-ENG-EARN", LocalDate.of(2026, 3, 31));
        assertThat(revenue(tenant)).as("nothing left to earn after the waiver").isEqualByComparingTo("5900.00");
    }

    // ---- IFRS 17 I3b ----------------------------------------------------------------------------------------------

    @Test
    void aClaimIsBookedAtApprovalAndPaidOutOfTheRailThatPaidIt() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-ENG-CLM", "END", null, "DIRECT", "DSM");
        UUID byBank = UUID.randomUUID();
        UUID byWallet = UUID.randomUUID();
        for (UUID claim : List.of(byBank, byWallet)) {
            publish(tenant, "claims.ClaimApproved", Map.of("claimId", claim, "policyNumber", "POL-ENG-CLM",
                "approvedAmount", money("24000.00"), "investmentComponent", "9000.00"));
        }
        // payment says how the first went; the second's completion was never recorded, so it reads as mobile money.
        publish(tenant, "payment.DisbursementCompleted", Map.of("disbursementId", UUID.randomUUID(),
            "purpose", "CLAIM_SETTLEMENT", "sourceRef", byBank.toString(), "method", "EFT",
            "amount", money("24000.00")));
        for (UUID claim : List.of(byBank, byWallet)) {
            publish(tenant, "claims.ClaimSettled", Map.of("claimId", claim, "policyNumber", "POL-ENG-CLM",
                "settledAmount", money("24000.00")));
        }

        assertThat(legs(tenant, "claims.ClaimApproved", byBank.toString()))
            .containsExactlyInAnyOrder("5110 DR 15000.00", "2124 DR 9000.00", "2211 CR 24000.00");
        assertThat(legs(tenant, "claims.ClaimSettled", byBank.toString()))
            .containsExactlyInAnyOrder("2211 DR 24000.00", "1130 CR 24000.00");
        assertThat(legs(tenant, "claims.ClaimSettled", byWallet.toString()))
            .containsExactlyInAnyOrder("2211 DR 24000.00", "1140 CR 24000.00");
        assertThat(open(tenant)).isEmpty();
    }

    @Test
    void aPayoutIsPayableWhenDueSplitByItsInvestmentComponentAndClearedNetOfTaxWhenPaid() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-ENG-MB", "MB", null, "AGENT", "ARU");
        publish(tenant, "benefitpayout.PayoutRequested", Map.of("instalmentId", "INS-1", "policyNumber", "POL-ENG-MB",
            "purpose", "SURVIVAL_BENEFIT_PAYOUT", "amount", money("1800.00"), "kind", "SURVIVAL",
            "grossAmount", "2000.00", "withheldAmount", "200.00", "investmentComponent", "1600.00"));
        publish(tenant, "benefitpayout.PayoutPaid", Map.of("instalmentId", "INS-1", "policyNumber", "POL-ENG-MB",
            "kind", "SURVIVAL", "paidAmount", money("1800.00"), "grossAmount", money("2000.00"),
            "withheldAmount", money("200.00")));

        assertThat(legs(tenant, "benefitpayout.PayoutRequested", "INS-1"))
            .containsExactlyInAnyOrder("2124 DR 1600.00", "5115 DR 400.00", "2214 CR 2000.00");
        assertThat(legs(tenant, "benefitpayout.PayoutPaid", "INS-1"))
            .containsExactlyInAnyOrder("2214 DR 2000.00", "1140 CR 1800.00", "2615 CR 200.00");
    }

    @Test
    void commissionIsEarnedAgainstItsChannelsPayableAndPaidNetOfTheTaxWithheld() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-ENG-COM", "TERM", null, "AGENT", "DSM");
        UUID accrual = UUID.randomUUID();
        UUID statement = UUID.randomUUID();
        publish(tenant, "distribution.CommissionAccrued", Map.of("accrualId", accrual, "statementId", statement,
            "agentId", UUID.randomUUID(), "policyNumber", "POL-ENG-COM", "tierType", "FIRST_YEAR",
            "salesChannel", "AGENT", "amount", money("240000.00")));
        publish(tenant, "distribution.CommissionPaid", Map.of("statementId", statement, "amount", money("240000.00"),
            "withheldAmount", "12000.00", "paidAmount", "228000.00", "salesChannel", "AGENT"));

        assertThat(legs(tenant, "distribution.CommissionAccrued", accrual.toString()))
            .containsExactlyInAnyOrder("2123 DR 240000.00", "2510 CR 240000.00");
        assertThat(lines(tenant, accrual.toString())).filteredOn(l -> "2123".equals(l.get("account_code")))
            .extracting(l -> l.get("movement_type")).containsExactly("IACF_COM");
        assertThat(legs(tenant, "distribution.CommissionPaid", statement.toString()))
            .containsExactlyInAnyOrder("2510 DR 240000.00", "1140 CR 228000.00", "2610 CR 12000.00");
    }

    @Test
    void thePremiumLevyPostsOnlyOnceFinanceHasApprovedARate() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-ENG-LEVY", "TERM", null, "DIRECT", "DSM");
        UUID before = UUID.randomUUID();
        publish(tenant, "billing.PremiumCollected", Map.of("invoiceId", before, "policyNumber", "POL-ENG-LEVY",
            "amount", money("1000.00")));
        assertThat(legs(tenant, "billing.PremiumCollected", before.toString()))
            .as("no rate configured: no levy").containsExactlyInAnyOrder("1140 DR 1000.00", "2122 CR 1000.00");

        TenantContext.set(tenant);
        var proposed = api.proposePolicyElection(new tz.co.nlolo.lifeplatform.finaccounting.api.PolicyElectionInput(
            "PREMIUM_LEVY_RATE", "*", "1.5", LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam")), "TIRA levy"), "finance-one");
        api.approvePolicyElection(proposed.electionId(), "Board minute 3/2026", "finance-two");

        UUID after = UUID.randomUUID();
        publish(tenant, "billing.PremiumCollected", Map.of("invoiceId", after, "policyNumber", "POL-ENG-LEVY",
            "amount", money("1000.00")));
        assertThat(legs(tenant, "billing.PremiumCollected", after.toString())).containsExactlyInAnyOrder(
            "1140 DR 1000.00", "2122 CR 1000.00", "5220 DR 15.00", "2650 CR 15.00");
    }

    @Test
    void anIfrs9ContractsInvoicePostsNothingAndQueuesNothing() {
        UUID tenant = UUID.randomUUID();
        issue(tenant, "POL-ENG-SAV", "SAV", null, "DIRECT", "DSM");
        UUID invoiceId = invoice(tenant, "POL-ENG-SAV", "500.00", null);
        assertThat(lines(tenant, invoiceId.toString())).isEmpty();
        assertThat(open(tenant)).isEmpty();
    }

    /** The legs of one journal, as "account DIRECTION amount". */
    private List<String> legs(UUID tenant, String sourceEvent, String sourceRef) {
        TenantContext.set(tenant);
        return jdbc.queryForList("SELECT account_code, direction, amount FROM finaccounting.gl_posting"
                + " WHERE source_event = ? AND source_ref = ?", sourceEvent, sourceRef).stream()
            .map(l -> l.get("account_code") + " " + l.get("direction") + " " + l.get("amount")).toList();
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currencyCode", "TZS");
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private void issue(UUID tenant, String policyNumber, String portfolio, String override, String channel,
                       String branch) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("issueDate", "2026-01-01");
        payload.put("productId", UUID.randomUUID());
        payload.put("productVersionId", UUID.randomUUID());
        payload.put("portfolioCode", portfolio);
        payload.put("cohortYear", 2026);
        payload.put("profitabilityBucket", "REMAINING");
        payload.put("measurementModelOverride", override);
        payload.put("salesChannel", channel);
        payload.put("branchCode", branch);
        publish(tenant, "policy.PolicyIssued", payload);
    }

    private UUID invoice(UUID tenant, String policyNumber, String amount, String cover) {
        UUID invoiceId = UUID.randomUUID();
        publish(tenant, "billing.PremiumInvoiceGenerated", invoicePayload(invoiceId, policyNumber, amount, cover));
        return invoiceId;
    }

    /** {@code cover} is {@code member|from|to|amount}, or null for an invoice that states none. */
    private static Map<String, Object> invoicePayload(UUID invoiceId, String policyNumber, String amount, String cover) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceId", invoiceId);
        payload.put("policyNumber", policyNumber);
        payload.put("dueDate", "2026-01-01");
        payload.put("amount", Map.of("amount", amount, "currencyCode", "TZS"));
        if (cover != null) {
            String[] f = cover.split("\\|", -1);
            payload.put("covers", List.of(Map.of("memberRef", f[0], "coversFrom", f[1], "coversTo", f[2], "amount", f[3])));
        }
        return payload;
    }

    private void earn(UUID tenant, String policyNumber, LocalDate through) {
        TenantContext.set(tenant);
        earning.earn(tenant, policyNumber, through);
    }

    /** Inside a committed transaction, so the AFTER_COMMIT listeners fire exactly as they do behind the emitters. */
    private void publish(UUID tenant, String type, Map<String, Object> payload) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
            events.publishEvent(DomainEventEnvelope.of(type, tenant, payload)));
    }

    private List<UnpostedEventView> open(UUID tenant) {
        TenantContext.set(tenant);
        return queue.unpostedEvents(true);
    }

    private Map<String, Object> journal(UUID tenant, String sourceRef) {
        TenantContext.set(tenant);
        return jdbc.queryForMap("SELECT * FROM finaccounting.journal_entry WHERE source_ref = ?", sourceRef);
    }

    private List<Map<String, Object>> lines(UUID tenant, String sourceRef) {
        TenantContext.set(tenant);
        return jdbc.queryForList("SELECT * FROM finaccounting.gl_posting WHERE source_ref = ?", sourceRef);
    }

    private BigDecimal revenue(UUID tenant) {
        TenantContext.set(tenant);
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN direction = 'CR' THEN amount ELSE -amount END), 0)"
            + " FROM finaccounting.gl_posting WHERE account_code = '4160'", BigDecimal.class);
    }
}
