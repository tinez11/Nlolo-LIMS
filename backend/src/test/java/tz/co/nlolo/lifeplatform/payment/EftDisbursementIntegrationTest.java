package tz.co.nlolo.lifeplatform.payment;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;
import tz.co.nlolo.lifeplatform.payment.api.DisbursementStatus;
import tz.co.nlolo.lifeplatform.payment.api.DisbursementStatusView;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.payment.application.PaymentApiImpl;
import tz.co.nlolo.lifeplatform.payment.domain.DisbursementInstruction;
import tz.co.nlolo.lifeplatform.payment.infrastructure.DisbursementInstructionRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The EFT rail, end to end: a credit-life claim payout is recorded, posted to the general ledger,
 * and never touched by the mobile-money gateway; finance executes it in the bank and confirms it
 * here; the accrual reverses and the claim's own settlement posting takes over.
 *
 * <p><b>Why this rail exists.</b> Every disbursement this platform has ever made went to
 * {@code MobileMoneyGatewayAdapter}, because {@code PaymentGatewayPort} exposes
 * {@code submitDisbursement} and nothing else. In this environment that gateway is a mock with no
 * authentication. A credit-life claim pays the borrower's outstanding loan balance to their
 * LENDER — millions of shillings, to a bank, on a book where several hundred borrowers arrive at
 * a time. That payment does not belong on a mobile-money rail, and the platform must not pretend
 * to have moved money it did not move.
 *
 * <p><b>The WireMock stub below is armed for every test, deliberately.</b> A gateway that is not
 * stubbed also fails to receive a request, so a test asserting "the gateway was never called"
 * against an unstubbed rail proves nothing — it would pass identically if the EFT branch did not
 * exist and the call simply errored. Arming it makes {@code verify(exactly(0), ...)} falsifiable:
 * the only reason no request arrives is that no request was made.
 *
 * <p>Runs as {@code app_role} (NOSUPERUSER NOBYPASSRLS) against real Postgres, and applies
 * {@code policyloan/V1}/{@code V2} despite never touching a loan — {@code gl_posting}'s
 * hand-written partitions inherit RLS and append-only privileges only through {@code
 * policyloan/V2}'s {@code trg_partition_controls}, the same structural reason
 * {@code ClaimAndCommissionPostingEndToEndTest} needs them.
 *
 * <p><b>Not asserted here, and deliberately not built: that an EFT payee looks like a bank
 * account rather than a phone number.</b> No source on this platform specifies a Tanzanian bank
 * account format, and {@code payeeRef} is an opaque string everywhere else in {@code payment} (it
 * carries an MSISDN, a bank account or an agent reference depending on the purpose). A regex
 * invented here would reject real accounts while proving nothing, so the guard that IS enforced —
 * and tested below — is the state machine: an EFT is the only thing a person may hand-complete,
 * and a gateway-owned payout refuses.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class EftDisbursementIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "eft_disbursement_password";
    private static final String CURRENCY = "TZS";
    /** A real credit-life number: the lender is owed the borrower's outstanding balance, not a
     * mobile-money top-up. This is the amount the whole rail exists for. */
    private static final String MILLIONS = "4750000.00";
    private static final String LENDER_BANK_ACCOUNT = "CRDB-0152-3311-9004";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @DynamicPropertySource
    static void mobileMoneyProperties(DynamicPropertyRegistry registry) {
        registry.add("mobile-money.base-url", () -> wireMock.baseUrl());
    }

    @BeforeAll
    static void startGatewayAndApplyMigrations() throws Exception {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            // V2, not just V1. V1s RLS predicate casts current_setting(...) straight to uuid, so an
            // unset app.current_tenant_id RAISES instead of matching nothing -- the audit listener
            // then fails to persist the envelope and logs a stack trace nobody reads. V2 wraps it in
            // NULLIF. Applying only V1 leaves every audit assertion in this class hostage to
            // whether a tenant happened to be set on the right connection.
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql",
            "db-migrations/payment/V5__rls_fail_closed.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql",
            "db-migrations/payment/V8__benefit_payout_purposes.sql",
            "db-migrations/payment/V9__account_purposes.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql",
            "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql",
            "db-migrations/finaccounting/V7__q4_2026_partitions.sql",
            "db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql",
            "db-migrations/finaccounting/V11__groups_and_policy_classification.sql",
            "db-migrations/finaccounting/V12__unposted_events_and_paa_earning.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DisbursementInstructionRepository disbursementRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private GlPostingRepository glPostingRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PaymentApi paymentApi;
    @Autowired private PaymentApiImpl paymentApiImpl;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
        wireMock.resetAll();
    }

    @Test
    void anEftDisbursementIsRecordedAndPostedButNeverSentToTheGateway() {
        // THE POINT OF THIS TASK. The only existing rail is a mobile-money gateway against a mock
        // with no authentication. A multi-million-shilling lender payout must not go near it.
        armTheGateway();
        UUID tenantId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();

        requestClaimSettlement(tenantId, claimId, "EFT");

        DisbursementInstruction instruction = instruction(tenantId, claimId);
        assertThat(instruction.getStatus()).isEqualTo("AWAITING_EXECUTION");
        assertThat(instruction.getMethod()).isEqualTo("EFT");
        assertThat(instruction.getGatewayReference()).isNull();
        assertThat(instruction.getExecutedBy()).isNull();
        assertThat(instruction.getExecutedAt()).isNull();

        wireMock.verify(exactly(0), postRequestedFor(urlPathEqualTo("/disburse")));

        // And it is on the books the moment it is real, rather than a week later when somebody
        // gets to the bank: DR 5100 Claims Expense / CR 2110 Claims Payable.
        JournalEntry accrual = singleEntryFor(tenantId, "payment.EftDisbursementAwaitingExecution", claimId.toString());
        List<GlPosting> legs = legsFor(tenantId, accrual);
        assertThat(legs).hasSize(2);
        assertThat(legFor(legs, "5110").getAmount()).isEqualByComparingTo(MILLIONS);
        assertThat(legFor(legs, "2211").getAmount()).isEqualByComparingTo(MILLIONS);
        assertThat(legFor(legs, "5110").getDirection().name()).isEqualTo("DR");
        assertThat(legFor(legs, "2211").getDirection().name()).isEqualTo("CR");

        // It also shows up on finance's work queue -- which is the only way anyone finds out they
        // owe it. An endpoint nobody can navigate to would make the whole rail unusable.
        TenantContext.set(tenantId);
        List<DisbursementStatusView> awaiting = paymentApi.listAwaitingEftExecution();
        assertThat(awaiting).extracting(DisbursementStatusView::disbursementId)
            .containsExactly(instruction.getDisbursementId());
        assertThat(awaiting.get(0).status()).isEqualTo(DisbursementStatus.AWAITING_EXECUTION);
        assertThat(awaiting.get(0).amount()).isEqualByComparingTo(MILLIONS);
    }

    @Test
    void financeMarksAnEftExecutedAndThatIsWhatCompletesIt() {
        // The transfer happens in a bank portal, out of band. What the platform records is that a
        // person confirmed it, and when -- the same shape as reconciling a field receipt.
        armTheGateway();
        UUID tenantId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        requestClaimSettlement(tenantId, claimId, "EFT");
        UUID disbursementId = instruction(tenantId, claimId).getDisbursementId();
        Instant before = Instant.now();

        TenantContext.set(tenantId);
        paymentApiImpl.markEftExecuted(disbursementId, "FT26091200417", "finance-officer-asha");

        DisbursementInstruction executed = instruction(tenantId, claimId);
        assertThat(executed.getStatus()).isEqualTo("COMPLETED");
        assertThat(executed.getGatewayReference()).isEqualTo("FT26091200417");
        assertThat(executed.getExecutedBy()).isEqualTo("finance-officer-asha");
        assertThat(executed.getExecutedAt()).isNotNull();

        // Falsifiable proof the completion event really went out -- this is what settles the claim
        // and takes the borrower off cover, so a silent state change would be worse than useless.
        assertThat(auditRows(tenantId, "payment.DisbursementCompleted", before)).hasSize(1);

        // The accrual reverses: DR 2110 Claims Payable / CR 5100 Claims Expense. Without this the
        // claims expense would be recognised twice -- once here and once by claims.ClaimSettled.
        JournalEntry reversal = singleEntryFor(tenantId, "payment.EftDisbursementExecuted", claimId.toString());
        List<GlPosting> legs = legsFor(tenantId, reversal);
        assertThat(legFor(legs, "2211").getDirection().name()).isEqualTo("DR");
        assertThat(legFor(legs, "5110").getDirection().name()).isEqualTo("CR");

        // Net effect of the pair on the payable: zero. The liability existed exactly as long as
        // the money was owed and not yet paid.
        assertThat(netMovement(tenantId, "2211")).isEqualByComparingTo("0.00");

        // Confirming twice is a finance officer clicking twice, not a second payout.
        paymentApiImpl.markEftExecuted(disbursementId, "FT26091200417", "finance-officer-asha");
        assertThat(auditRows(tenantId, "payment.DisbursementCompleted", before)).hasSize(1);
        assertThat(entriesFor(tenantId, "payment.EftDisbursementExecuted", claimId.toString())).hasSize(1);

        wireMock.verify(exactly(0), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void aMobileMoneyDisbursementStillGoesToTheGatewayExactlyAsBefore() {
        // The regression guard. Every existing payout path must be untouched -- and "untouched"
        // includes the ledger: an ordinary claim payout raises no claims-payable accrual, because
        // for that rail there is no window in which the money is owed but unpaid.
        armTheGateway();
        UUID tenantId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();

        requestClaimSettlement(tenantId, claimId, null);

        DisbursementInstruction instruction = instruction(tenantId, claimId);
        assertThat(instruction.getStatus()).isEqualTo("COMPLETED");
        assertThat(instruction.getMethod()).isEqualTo("MOBILE_MONEY");
        assertThat(instruction.getGatewayReference()).isEqualTo("MM-EFT-REGRESSION");
        assertThat(instruction.getExecutedBy()).isNull();

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));

        assertThat(entriesFor(tenantId, "payment.EftDisbursementAwaitingExecution", claimId.toString())).isEmpty();
        assertThat(entriesFor(tenantId, "payment.EftDisbursementExecuted", claimId.toString())).isEmpty();
    }

    @Test
    void financeCannotHandCompleteAPayoutTheGatewayOwns() {
        // The rails must not be interchangeable in this direction either. A mobile-money row is
        // COMPLETED by the aggregator's callback; letting a person type a reference into it would
        // mark money as moved on nobody's authority but their own.
        armTheGateway();
        UUID tenantId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        requestClaimSettlement(tenantId, claimId, null);
        UUID disbursementId = instruction(tenantId, claimId).getDisbursementId();

        TenantContext.set(tenantId);
        assertThatThrownBy(() -> paymentApiImpl.markEftExecuted(disbursementId, "FT-MADE-UP", "someone"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not awaiting execution");
    }

    @Test
    void anExecutedEftNeedsTheBankReferenceAndWhoExecutedIt() {
        // The only evidence the platform will ever hold that this payout left. Without it a
        // settled claim is a claim somebody said they paid.
        armTheGateway();
        UUID tenantId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        requestClaimSettlement(tenantId, claimId, "EFT");
        UUID disbursementId = instruction(tenantId, claimId).getDisbursementId();

        TenantContext.set(tenantId);
        assertThatThrownBy(() -> paymentApiImpl.markEftExecuted(disbursementId, "  ", "finance-officer-asha"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bank reference");
        assertThatThrownBy(() -> paymentApiImpl.markEftExecuted(disbursementId, "FT26091200999", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("who executed it");

        // Neither refusal left the row half-moved.
        assertThat(instruction(tenantId, claimId).getStatus()).isEqualTo("AWAITING_EXECUTION");
    }

    // ---- fixtures ----

    /** Armed even where the assertion is that nothing reaches it -- see this class's javadoc on
     * why an unstubbed gateway would make {@code verify(exactly(0), ...)} vacuous. */
    private void armTheGateway() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-EFT-REGRESSION\"}")));
    }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    /**
     * Publishes the real {@code claims.ClaimSettlementRequested} shape rather than calling
     * {@code ClaimsApi}, so this class needs neither the policy nor the claims schema. The payload
     * is field-for-field what {@code ClaimsApiImpl.decideSettlement} publishes;
     * {@code CreditLifeClaimEndToEndTest} is where the claims half of the chain is proved.
     *
     * <p>A null {@code method} omits the key entirely, which is exactly what every publisher
     * before credit life does — that absence, not a literal "MOBILE_MONEY", is the regression
     * case.
     */
    private void requestClaimSettlement(UUID tenantId, UUID claimId, String method) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idempotencyKey", "claim-" + claimId);
        payload.put("claimId", claimId);
        payload.put("payeeRef", method == null ? "MPESA-0712345678" : LENDER_BANK_ACCOUNT);
        payload.put("amount", Map.of("amount", MILLIONS, "currencyCode", CURRENCY));
        if (method != null) {
            payload.put("disbursementMethod", method);
        }
        DomainEventEnvelope<Map<String, Object>> envelope =
            DomainEventEnvelope.of("claims.ClaimSettlementRequested", tenantId, payload);
        // The tenant must be set BEFORE the publishing transaction opens, not only inside the
        // listener: TenantAwareDataSource sets app.current_tenant_id at connection-ACQUISITION
        // time, and the audit listener that persists this envelope runs on this same connection.
        TenantContext.set(tenantId);
        // AFTER_COMMIT listeners run synchronously on this thread once the commit returns.
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));
    }

    private DisbursementInstruction instruction(UUID tenantId, UUID claimId) {
        TenantContext.set(tenantId);
        return disbursementRepository.findByIdempotencyKeyAndTenantId("claim-" + claimId, tenantId)
            .orElseThrow(() -> new AssertionError("Expected a disbursement_instruction row for claim " + claimId));
    }

    private List<AuditLogEntry> auditRows(UUID tenantId, String eventType, Instant before) {
        TenantContext.set(tenantId);
        return auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, eventType, before.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    private List<JournalEntry> entriesFor(UUID tenantId, String sourceEvent, String sourceRef) {
        TenantContext.set(tenantId);
        return journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).stream()
            .filter(e -> sourceEvent.equals(e.getSourceEvent()) && sourceRef.equals(e.getSourceRef()))
            .toList();
    }

    private JournalEntry singleEntryFor(UUID tenantId, String sourceEvent, String sourceRef) {
        List<JournalEntry> entries = entriesFor(tenantId, sourceEvent, sourceRef);
        assertThat(entries).as("expected exactly one %s journal entry for %s", sourceEvent, sourceRef).hasSize(1);
        return entries.get(0);
    }

    private List<GlPosting> legsFor(UUID tenantId, JournalEntry entry) {
        TenantContext.set(tenantId);
        return glPostingRepository.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(tenantId, entry.getJournalEntryId());
    }

    private static GlPosting legFor(List<GlPosting> legs, String accountCode) {
        return legs.stream().filter(l -> accountCode.equals(l.getAccountCode())).findFirst()
            .orElseThrow(() -> new AssertionError("No leg found for account " + accountCode));
    }

    /** DR minus CR across every posting this tenant has against one account. */
    private BigDecimal netMovement(UUID tenantId, String accountCode) {
        TenantContext.set(tenantId);
        return journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).stream()
            .flatMap(e -> legsFor(tenantId, e).stream())
            .filter(l -> accountCode.equals(l.getAccountCode()))
            .map(l -> "DR".equals(l.getDirection().name()) ? l.getAmount() : l.getAmount().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
