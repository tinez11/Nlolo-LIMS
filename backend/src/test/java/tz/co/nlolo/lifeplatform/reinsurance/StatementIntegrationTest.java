package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.SettlementView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.StatementNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.StatementStateException;
import tz.co.nlolo.lifeplatform.reinsurance.api.StatementView;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
import tz.co.nlolo.lifeplatform.reinsurance.application.BordereauJob;
import tz.co.nlolo.lifeplatform.reinsurance.application.ReinsuranceStatements;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IFRS 17 I3d -- the quarterly statement inside reinsurance: prepared from the quarter's bordereaux and recoveries,
 * completed from the reinsurer's statement, approved by a second person, published as reinsurance.StatementApproved
 * (finaccounting posts it -- {@code ReinsuranceAndLoanPostingEndToEndTest}). Real Postgres, app_role, RLS; the
 * quarter is fixed (2026-Q1) and "today" passed in, so it runs the same whatever the date.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(StatementIntegrationTest.EventRecorderConfiguration.class)
class StatementIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "statement_it_password";
    private static final String CURRENCY = "TZS";
    private static final LocalDate TREATY_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate AFTER_Q1 = LocalDate.of(2026, 4, 2);

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
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            "db-migrations/reinsurance/V4__projection_product_category.sql",
            "db-migrations/reinsurance/V5__bordereau.sql",
            "db-migrations/reinsurance/V6__scheme_may_open_empty.sql",
            "db-migrations/reinsurance/V7__statement.sql",
            "db-migrations/reinsurance/V8__projection_portfolio.sql", "db-migrations/reinsurance/V9__scheme_premium_may_be_nil.sql", "db-migrations/reinsurance/V10__acted_alone.sql", "db-migrations/reinsurance/V11__settlement.sql", "db-migrations/reinsurance/V12__free_look_reversal.sql", "db-migrations/reinsurance/V13__treaty_needs_approval.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @TestConfiguration
    static class EventRecorderConfiguration {
        @Bean
        EventRecorder statementTestEventRecorder() { return new EventRecorder(); }
    }

    static class EventRecorder {
        private final List<DomainEventEnvelope<?>> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void record(DomainEventEnvelope<?> envelope) { received.add(envelope); }

        List<DomainEventEnvelope<?>> of(UUID tenantId, String type) {
            return received.stream().filter(e -> type.equals(e.eventType()) && tenantId.equals(e.tenantId())).toList();
        }

        List<DomainEventEnvelope<?>> statementsOf(UUID tenantId) {
            return received.stream().filter(e -> "reinsurance.StatementApproved".equals(e.eventType())
                && tenantId.equals(e.tenantId())).toList();
        }
    }

    @Autowired private ReinsuranceApi api;
    @Autowired private ReinsuranceStatements statements;
    @Autowired private tz.co.nlolo.lifeplatform.reinsurance.application.StatementSettlements settlements;
    @Autowired private BordereauJob bordereauJob;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EventRecorder recorder;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private void publish(UUID tenantId, String type, Map<String, Object> payload) {
        TenantContext.set(tenantId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            eventPublisher.publishEvent(DomainEventEnvelope.of(type, tenantId, payload)));
    }

    private TreatyView quotaShare(UUID tenantId, String percent, String commission) {
        TenantContext.set(tenantId);
        return api.approveTreaty(api.createTreaty(new ReinsuranceApi.CreateTreatyRequest("Africa Re", TreatyType.QUOTA_SHARE,
            new BigDecimal("0.00"), CURRENCY, new BigDecimal(percent), new BigDecimal(commission), null,
            TREATY_FROM, null), "finance-officer").treatyId(), "treaty-approver");
    }

    private void activate(UUID tenantId, String premium, LocalDate on) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("policyNumber", "POL-STM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        payload.put("productId", UUID.randomUUID());
        payload.put("productCategory", "TERM_LIFE");
        payload.put("issueDate", on.toString());
        payload.put("activatedAt", on.toString());
        payload.put("premiumFrequency", "MONTHLY");
        payload.put("sumAssured", Map.of("amount", "2000000.00", "currencyCode", CURRENCY));
        payload.put("premium", Map.of("amount", premium, "currencyCode", CURRENCY));
        publish(tenantId, "policy.PolicyActivated", payload);
    }

    /** Q1 2026 with every bordereau written: Jan-Mar of a 50% quota share, 100,000 monthly, 20% commission. */
    private TreatyView settleableQ1(UUID tenantId) {
        TreatyView treaty = quotaShare(tenantId, "50.00", "20.00");
        activate(tenantId, "100000.00", LocalDate.of(2026, 1, 5));
        bordereauJob.drain(LocalDate.of(2026, 4, 1));
        TenantContext.set(tenantId);
        return treaty;
    }

    private StatementView submitted(StatementView draft, String withheld, String profitCommission) {
        api.attachStatementDocument(draft.statementId(), "doc-" + draft.quarter(), draft.preparer());
        api.updateStatement(draft.statementId(), new BigDecimal(withheld), new BigDecimal(profitCommission),
            "Africa Re statement agreed", draft.preparer());
        return api.submitStatement(draft.statementId(), draft.preparer());
    }

    @Test
    void aQuarterIsPreparedFromItsBordereauxAndApprovedByASecondPerson() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settleableQ1(tenant);
        StatementView draft = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1);
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.premium()).isEqualByComparingTo("150000.00");      // 3 months x 50% of 100,000
        assertThat(draft.commission()).isEqualByComparingTo("30000.00");    // 20% of it
        assertThat(draft.recoveries()).isZero();
        assertThat(draft.items()).filteredOn(i -> i.type().equals("BORDEREAU")).hasSize(3);
        assertThat(draft.owedByUs()).isEqualByComparingTo("120000.00");
        assertThat(draft.journal()).extracting(l -> l.entry() + " " + l.side() + " " + l.account())
            .containsExactly("R-01 DR 1430", "R-01 CR 1431", "R-01 CR 1434");

        assertThatThrownBy(() -> api.submitStatement(draft.statementId(), "finance-one"))
            .isInstanceOf(ReinsuranceValidationException.class)
            .hasMessageContaining("Attach the reinsurer's statement").hasMessageContaining("Say why");
        StatementView waiting = submitted(draft, "20000.00", "5000.00");
        assertThat(waiting.status()).isEqualTo("SUBMITTED");
        assertThatThrownBy(() -> api.approveStatement(draft.statementId(), "finance-one"))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("second person");

        StatementView approved = api.approveStatement(draft.statementId(), "finance-approver");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.decidedBy()).isEqualTo("finance-approver");
        assertThat(recorder.statementsOf(tenant)).singleElement().satisfies(e -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) e.payload();
            assertThat(p.get("statementId")).isEqualTo(draft.statementId().toString());
            assertThat(p.get("owedByUs")).isEqualTo(Map.of("amount", "100000.00", "currencyCode", CURRENCY));
            assertThat(p.get("fundsWithheld")).isEqualTo(Map.of("amount", "20000.00", "currencyCode", CURRENCY));
            assertThat(p.get("profitCommission")).isEqualTo(Map.of("amount", "5000.00", "currencyCode", CURRENCY));
        });
    }

    // 2026-11-08: what the statement left in 1434, 1433 and 2550 had no way out but a free-form manual journal.
    @Test
    @SuppressWarnings("unchecked")
    void anApprovedStatementIsSettledOneConfirmedMovementAtATimeNeverBeyondWhatItLeftOpen() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settleableQ1(tenant);
        StatementView draft = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1);
        submitted(draft, "20000.00", "5000.00");
        UUID statementId = draft.statementId();
        api.approveStatement(statementId, "finance-approver");

        // We owe 150,000 premium less 20,000 withheld less 30,000 commission; the 20,000 withheld and 5,000 profit
        // commission wait on their own.
        SettlementView.Open open = settlements.open(statementId);
        assertThat(open.theyOweUs()).isZero();
        assertThat(open.weOweThem()).isEqualByComparingTo("100000.00");
        assertThat(open.withheldHeld()).isEqualByComparingTo("20000.00");
        assertThat(open.profitCommissionDue()).isEqualByComparingTo("5000.00");
        assertThatThrownBy(() -> settlements.record(statementId, received("RECEIVED", "1.00"), "finance-one"))
            .isInstanceOf(ReinsuranceValidationException.class).hasMessageContaining("owes nothing more");

        // Funds withheld released: confirmed by a second person, it adds to what we owe.
        SettlementView release = settlements.record(statementId, new SettlementView.NewSettlement("WITHHELD_RELEASED",
            new BigDecimal("20000.00"), null, null, null, null, null, null, null, "Security released at year end"),
            "finance-one");
        assertThat(release.status()).isEqualTo("AWAITING_APPROVAL");
        assertThatThrownBy(() -> settlements.approve(release.settlementId(), "finance-one"))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("second person");
        assertThat(settlements.approve(release.settlementId(), "finance-approver").status()).isEqualTo("POSTED");
        assertThat(recorder.of(tenant, "reinsurance.FundsWithheldReleased")).hasSize(1);
        assertThat(settlements.open(statementId).weOweThem()).isEqualByComparingTo("120000.00");

        // Paying them: confirmed, an EFT to their bank account; a second payment while it waits is refused.
        SettlementView pay = settlements.record(statementId, new SettlementView.NewSettlement("PAID",
            new BigDecimal("120000.00"), null, null, null, "CRDB", "Kariakoo", "Africa Re", "0150123456789", null),
            "finance-one");
        assertThat(settlements.approve(pay.settlementId(), "finance-approver").status()).isEqualTo("REQUESTED");
        assertThat(recorder.of(tenant, "reinsurance.SettlementPayoutRequested")).singleElement().satisfies(e -> {
            Map<String, Object> p = (Map<String, Object>) e.payload();
            assertThat(p.get("disbursementMethod")).isEqualTo("EFT");
            assertThat(p.get("payeeAccountNumber")).isEqualTo("0150123456789");
            assertThat(p.get("idempotencyKey")).isEqualTo("reinsurance-settlement:" + pay.settlementId());
        });
        assertThatThrownBy(() -> settlements.record(statementId, new SettlementView.NewSettlement("PAID",
            new BigDecimal("1.00"), null, null, null, "CRDB", null, "Africa Re", "0150123456789", null), "finance-one"))
            .isInstanceOf(ReinsuranceValidationException.class).hasMessageContaining("Nothing more is owed");

        // The transfer made under Payments to make: paid, and posted.
        publish(tenant, "payment.DisbursementCompleted", Map.of("purpose", "REINSURANCE_SETTLEMENT",
            "sourceRef", pay.settlementId().toString()));
        TenantContext.set(tenant);
        assertThat(settlements.open(statementId).settlements()).filteredOn(s -> s.settlementId().equals(pay.settlementId()))
            .singleElement().extracting(SettlementView::status).isEqualTo("PAID");
        assertThat(recorder.of(tenant, "reinsurance.SettlementPaid")).hasSize(1);

        // Profit commission received, by bank deposit -- never more than the statement booked.
        assertThatThrownBy(() -> settlements.record(statementId, received("PROFIT_COMMISSION", "5000.01"), "finance-one"))
            .isInstanceOf(ReinsuranceValidationException.class).hasMessageContaining("profit commission still due");
        SettlementView pc = settlements.record(statementId, received("PROFIT_COMMISSION", "5000.00"), "finance-one");
        settlements.approve(pc.settlementId(), "finance-approver");
        assertThat(recorder.of(tenant, "reinsurance.ProfitCommissionReceived")).singleElement()
            .satisfies(e -> assertThat(((Map<String, Object>) e.payload()).get("receiptMethod")).isEqualTo("BANK_DEPOSIT"));

        SettlementView.Open closed = settlements.open(statementId);
        assertThat(closed.weOweThem()).isZero();
        assertThat(closed.theyOweUs()).isZero();
        assertThat(closed.profitCommissionDue()).isZero();
        assertThat(closed.withheldHeld()).isZero();
    }

    @Test
    void aSettlementIsRefusedSayingWhyAndFreesWhatItHeld() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settleableQ1(tenant);
        StatementView draft = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1);
        submitted(draft, "0.00", "5000.00");
        assertThatThrownBy(() -> settlements.record(draft.statementId(), received("PROFIT_COMMISSION", "5000.00"),
            "finance-one")).isInstanceOf(StatementStateException.class).hasMessageContaining("approved");
        api.approveStatement(draft.statementId(), "finance-approver");

        SettlementView pc = settlements.record(draft.statementId(), received("PROFIT_COMMISSION", "5000.00"), "finance-one");
        assertThat(settlements.open(draft.statementId()).profitCommissionDue()).isZero();
        assertThatThrownBy(() -> settlements.refuse(pc.settlementId(), " ", "finance-approver"))
            .isInstanceOf(ReinsuranceValidationException.class);
        SettlementView refused = settlements.refuse(pc.settlementId(), "Not on the bank statement", "finance-approver");
        assertThat(refused.status()).isEqualTo("REFUSED");
        assertThat(settlements.open(draft.statementId()).profitCommissionDue()).isEqualByComparingTo("5000.00");
    }

    private static SettlementView.NewSettlement received(String kind, String amount) {
        return new SettlementView.NewSettlement(kind, new BigDecimal(amount), "BANK_DEPOSIT", "CRDB-TRF-0091",
            LocalDate.of(2026, 4, 20), null, null, null, null, null);
    }

    @Test
    void aQuarterIsRefusedBeforeItEndsOrWhileABordereauIsMissing() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = quotaShare(tenant, "50.00", "0");
        assertThatThrownBy(() -> statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", LocalDate.of(2026, 3, 31)))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("has not ended");
        assertThatThrownBy(() -> statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("2026-01").hasMessageContaining("bordereau");
        assertThatThrownBy(() -> statements.prepare(treaty.treatyId(), "2026-Q9", "finance-one", AFTER_Q1))
            .isInstanceOf(ReinsuranceValidationException.class).hasMessageContaining("YYYY-Qn");
    }

    @Test
    void aQuarterIsSettledOnceAndAgainOnlyAfterARejection() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settleableQ1(tenant);
        StatementView first = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1);
        assertThatThrownBy(() -> statements.prepare(treaty.treatyId(), "2026-Q1", "finance-two", AFTER_Q1))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("already has a statement");
        submitted(first, "0.00", "0.00");
        assertThatThrownBy(() -> api.rejectStatement(first.statementId(), " ", "finance-approver"))
            .isInstanceOf(ReinsuranceValidationException.class);
        assertThat(api.rejectStatement(first.statementId(), "Reinsurer disputes March", "finance-approver").status())
            .isEqualTo("REJECTED");

        StatementView again = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-two", AFTER_Q1);
        assertThat(again.status()).isEqualTo("DRAFT");
        assertThat(again.items()).hasSize(3);
    }

    @Test
    void onlyItsPreparerChangesADraftAndWithdrawsItAndFundsWithheldStayWithinThePremium() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settleableQ1(tenant);
        StatementView draft = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1);
        assertThatThrownBy(() -> api.requireStatementEditable(draft.statementId(), "finance-two"))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("who prepared it");
        assertThatThrownBy(() -> api.updateStatement(draft.statementId(), new BigDecimal("150000.01"), BigDecimal.ZERO,
                "x", "finance-one"))
            .isInstanceOf(ReinsuranceValidationException.class).hasMessageContaining("cannot exceed");

        submitted(draft, "0.00", "0.00");
        assertThatThrownBy(() -> api.requireStatementEditable(draft.statementId(), "finance-one"))
            .isInstanceOf(StatementStateException.class).hasMessageContaining("only a draft");
        assertThat(api.withdrawStatement(draft.statementId(), "finance-one").status()).isEqualTo("DRAFT");
    }

    /** A recovery recorded before I3c never posted 1420; settling it would clear what was never there. */
    @Test
    void legacyRecoveriesAreNeverSettledButPostedOnesAre() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settleableQ1(tenant);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO reinsurance.claim_recovery (tenant_id, claim_id, treaty_id, recoverable_amount,"
                + " created_at, legacy) VALUES (?, ?, ?, 900000.00, '2026-02-10T09:00:00Z', true)",
                tenant, UUID.randomUUID(), treaty.treatyId());
            jdbc.update("INSERT INTO reinsurance.claim_recovery (tenant_id, claim_id, treaty_id, recoverable_amount,"
                + " created_at) VALUES (?, ?, ?, 400000.00, '2026-03-10T09:00:00Z')",
                tenant, UUID.randomUUID(), treaty.treatyId());
        });
        StatementView draft = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1);
        assertThat(draft.recoveries()).isEqualByComparingTo("400000.00");
        assertThat(draft.items()).filteredOn(i -> i.type().equals("RECOVERY")).hasSize(1);
        assertThat(draft.owedToUs()).isEqualByComparingTo("280000.00");   // 400,000 + 30,000 - 150,000
    }

    @Test
    void anotherTenantSeesNoStatement() {
        UUID tenant = UUID.randomUUID();
        TreatyView treaty = settleableQ1(tenant);
        StatementView mine = statements.prepare(treaty.treatyId(), "2026-Q1", "finance-one", AFTER_Q1);
        assertThat(api.listStatements(null, treaty.treatyId())).extracting(StatementView::statementId)
            .containsExactly(mine.statementId());
        TenantContext.set(UUID.randomUUID());
        assertThat(api.listStatements(null, null)).isEmpty();
        assertThatThrownBy(() -> api.getStatement(mine.statementId())).isInstanceOf(StatementNotFoundException.class);
    }
}
