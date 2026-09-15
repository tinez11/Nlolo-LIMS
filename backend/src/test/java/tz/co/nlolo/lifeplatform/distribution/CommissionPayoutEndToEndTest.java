package tz.co.nlolo.lifeplatform.distribution;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionStatement;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionStatementRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.payment.domain.DisbursementInstruction;
import tz.co.nlolo.lifeplatform.payment.infrastructure.DisbursementInstructionRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * Task 8, Step 4 -- the real end-to-end proof of distribution's payout loop:
 * {@code DistributionApi.requestStatementPayout} (real API) -> {@code
 * distribution.CommissionPayoutRequested} (real event) -> {@code
 * payment.PaymentRequestListener.handleCommissionPayout} (this task's new branch) -> the
 * mobile-money rail (in-process WireMock, as {@code ClaimSettlementEndToEndTest} does) -> a real
 * {@code payment.disbursement_instruction} row with {@code purpose = 'COMMISSION_PAYOUT'} ->
 * {@code payment.DisbursementCompleted}/{@code DisbursementFailed} -> {@code
 * distribution.application.PaymentEventListener} -> the statement reaches {@code PAID} or {@code
 * PAYOUT_FAILED}.
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS), so RLS on both
 * {@code distribution.commission_statement} and {@code payment.disbursement_instruction} is
 * genuinely exercised across the hop, not merely declared.
 *
 * <p>AFTER_COMMIT chains are synchronous on one thread, so by the time {@code
 * requestStatementPayout} returns, the rail has been called and the outcome already applied. No
 * await/sleep anywhere in this class.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(CommissionPayoutEndToEndTest.EventRecorderConfiguration.class)
class CommissionPayoutEndToEndTest {

    private static final String APP_ROLE_PASSWORD = "commission_payout_e2e_password";
    private static final String CURRENCY = "TZS";

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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V4__seed_distribution_parameters.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
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
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    /** Records distribution.CommissionPaid so "exactly one, and none on redelivery" is assertable
     * directly rather than inferred from state. AFTER_COMMIT, the same phase real consumers use. */
    @TestConfiguration
    static class EventRecorderConfiguration {
        @Bean
        EventRecorder payoutTestEventRecorder() { return new EventRecorder(); }
    }

    static class EventRecorder {
        private final List<DomainEventEnvelope<?>> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void record(DomainEventEnvelope<?> envelope) { received.add(envelope); }

        void clear() { received.clear(); }

        List<DomainEventEnvelope<?>> ofType(String eventType) {
            return received.stream().filter(e -> eventType.equals(e.eventType())).toList();
        }
    }

    @Autowired private DistributionApi distributionApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private CommissionStatementRepository commissionStatementRepository;
    @Autowired private DisbursementInstructionRepository disbursementRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private EventRecorder eventRecorder;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
        wireMock.resetAll();
    }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    /**
     * Issues a real policy through {@code PolicyApi} so a real FIRST_YEAR accrual and its OPEN
     * statement exist, then closes that statement. Closing is done through the domain method
     * because the production closer is Task 9's pg_cron function, which cannot run inside this
     * test's synchronous chain -- the statement's CLOSED precondition is what this class is set up
     * to exercise, not how it got there.
     *
     * @return the CLOSED statement's id, with a total of 10% of the premium
     */
    private UUID closedStatementFor(UUID tenantId, String tag, BigDecimal premium) {
        TenantContext.set(tenantId);
        ProductSummaryView product = productApi.createProduct("DIST-PAYOUT-" + tag, "Distribution Payout Product " + tag,
            ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        UUID productVersionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        distributionApi.createCommissionPlan(product.productId(), List.of(
            new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, new BigDecimal("0.10"), null, null)),
            "actuary");

        PartyView agentParty = partyApi.registerIndividual("Distribution Payout Agent " + tag, LocalDate.of(1985, 1, 1),
            "+25573" + String.format("%07d", Math.abs(tag.hashCode() % 10000000)), null, "test-agent");
        partyApi.submitKycEvidence(agentParty.partyId(), tz.co.nlolo.lifeplatform.party.api.KycStatus.VERIFIED,
            "doc-ref-" + tag, "kyc-officer");
        UUID agentId = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            agentParty.partyId(), "LIC-PAYOUT-" + tag, LocalDate.now().plusYears(1), null), "staff-1").agentId();

        PartyView policyholder = partyApi.registerIndividual("Distribution Payout Policyholder " + tag,
            LocalDate.of(1980, 6, 1), "+25574" + String.format("%07d", Math.abs(tag.hashCode() % 10000000)), null, "test-agent");
        String policyNumber = policyApi.issuePolicy(null, new PolicyApi.IssueRequest(policyholder.partyId(),
            product.productId(), productVersionId, new BigDecimal("2000000"), CURRENCY, premium, CURRENCY,
            "MONTHLY", agentId, List.of(), "Distribution payout E2E test"), "test-staff").policyNumber();
        // Commission accrues on cover, not on an offer, so there is no statement to pay out
        // until the first premium has been collected.
        policyApi.activateOnFirstPremium(policyNumber);

        TenantContext.set(tenantId);
        List<CommissionStatement> statements = commissionStatementRepository
            .findByTenantIdAndAgentIdOrderByPeriodDesc(tenantId, agentId);
        assertThat(statements).as("issuance should have created exactly one statement").hasSize(1);
        UUID statementId = statements.get(0).getStatementId();

        transactionTemplate().executeWithoutResult(status -> {
            TenantContext.set(tenantId);
            CommissionStatement statement = commissionStatementRepository
                .findByStatementIdAndTenantId(statementId, tenantId).orElseThrow();
            statement.close(Instant.now());
            commissionStatementRepository.save(statement);
        });
        return statementId;
    }

    private StatementStatus statusOf(UUID tenantId, UUID statementId) {
        TenantContext.set(tenantId);
        return commissionStatementRepository.findByStatementIdAndTenantId(statementId, tenantId)
            .orElseThrow(() -> new AssertionError("Expected statement " + statementId))
            .getStatus();
    }

    @Test
    void requestingPayoutForAClosedStatementReachesTheRailOnceAndCreatesARealDisbursementRow() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-COMM-E2E-SUCCESS\"}")));

        UUID tenantId = UUID.randomUUID();
        UUID statementId = closedStatementFor(tenantId, "SUCCESS", new BigDecimal("100000.00"));
        String payoutKey = "payout-" + statementId;
        eventRecorder.clear();

        TenantContext.set(tenantId);
        distributionApi.requestStatementPayout(statementId, "MPESA-0713000001", payoutKey, "finance-officer");

        // A REAL payment.disbursement_instruction row, with the specific correlation Task 8 needs.
        DisbursementInstruction instruction = disbursementRepository
            .findByIdempotencyKeyAndTenantId(payoutKey, tenantId)
            .orElseThrow(() -> new AssertionError("Expected a disbursement_instruction row for key " + payoutKey));
        assertThat(instruction.getPurpose()).isEqualTo("COMMISSION_PAYOUT");
        assertThat(instruction.getSourceRef()).isEqualTo(statementId.toString());
        assertThat(instruction.getStatus()).isEqualTo("COMPLETED");
        assertThat(instruction.getGatewayReference()).isEqualTo("MM-COMM-E2E-SUCCESS");
        assertThat(instruction.getPayeeRef()).isEqualTo("MPESA-0713000001");
        // 10% of 100000.00 -- the accrual really drove the payout amount.
        assertThat(instruction.getAmount()).isEqualByComparingTo("10000.00");

        // The confirmation hop already ran, synchronously, in the same AFTER_COMMIT chain.
        assertThat(statusOf(tenantId, statementId)).isEqualTo(StatementStatus.PAID);
        assertThat(eventRecorder.ofType("distribution.CommissionPaid")).hasSize(1);

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void aRedeliveredDisbursementCompletedPublishesNoSecondCommissionPaid() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-COMM-E2E-REDELIVER\"}")));

        UUID tenantId = UUID.randomUUID();
        UUID statementId = closedStatementFor(tenantId, "REDELIVER", new BigDecimal("100000.00"));
        String payoutKey = "payout-" + statementId;
        eventRecorder.clear();

        TenantContext.set(tenantId);
        distributionApi.requestStatementPayout(statementId, "MPESA-0713000002", payoutKey, "finance-officer");
        assertThat(statusOf(tenantId, statementId)).isEqualTo(StatementStatus.PAID);
        assertThat(eventRecorder.ofType("distribution.CommissionPaid")).hasSize(1);

        // A real at-least-once redelivery of payment's own DisbursementCompleted envelope, built
        // field-for-field from PaymentApiImpl's published shape.
        publishDisbursementCompleted(tenantId, statementId, "COMMISSION_PAYOUT", "MM-COMM-E2E-REDELIVER");

        assertThat(statusOf(tenantId, statementId)).isEqualTo(StatementStatus.PAID);
        assertThat(eventRecorder.ofType("distribution.CommissionPaid"))
            .as("a redelivered DisbursementCompleted must not emit a second CommissionPaid")
            .hasSize(1);
    }

    @Test
    void aRailDeclineLeavesTheStatementPayoutFailedAndARetryWithANewKeyCanStillReachPaid() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            // "reason", not "failureReason" -- MobileMoneyGatewayAdapter:84 reads that field name.
            "{\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_FLOAT\"}")));

        UUID tenantId = UUID.randomUUID();
        UUID statementId = closedStatementFor(tenantId, "DECLINE", new BigDecimal("100000.00"));
        double failedBefore = meterRegistry.counter("lifeplatform_distribution_payout_failed_total").count();

        TenantContext.set(tenantId);
        distributionApi.requestStatementPayout(statementId, "MPESA-0713000003", "payout-fail-" + statementId, "finance-officer");

        assertThat(statusOf(tenantId, statementId)).isEqualTo(StatementStatus.PAYOUT_FAILED);
        TenantContext.set(tenantId);
        assertThat(commissionStatementRepository.findByStatementIdAndTenantId(statementId, tenantId)
            .orElseThrow().getPayoutFailureReason()).isEqualTo("INSUFFICIENT_FLOAT");
        // The failure is genuinely alertable, not just logged.
        assertThat(meterRegistry.counter("lifeplatform_distribution_payout_failed_total").count())
            .isEqualTo(failedBefore + 1);

        // The retry: a NEW idempotency key, or payment dedupes it away silently.
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-COMM-E2E-RETRY\"}")));
        TenantContext.set(tenantId);
        distributionApi.requestStatementPayout(statementId, "MPESA-0713000003", "payout-retry-" + statementId, "finance-officer");

        assertThat(statusOf(tenantId, statementId)).isEqualTo(StatementStatus.PAID);
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void republishingTheSamePayoutRequestWithTheSameKeyNeverReachesTheRailAgain() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-COMM-E2E-ONCE\"}")));

        UUID tenantId = UUID.randomUUID();
        UUID statementId = closedStatementFor(tenantId, "ONCE", new BigDecimal("100000.00"));
        String payoutKey = "payout-" + statementId;

        TenantContext.set(tenantId);
        distributionApi.requestStatementPayout(statementId, "MPESA-0713000004", payoutKey, "finance-officer");
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));

        // The same CommissionPayoutRequested envelope again, same idempotencyKey -- payment's own
        // registry claim must dedupe it before the rail is called a second time.
        Map<String, Object> payload = Map.of(
            "statementId", statementId,
            "payeeRef", "MPESA-0713000004",
            "amount", Map.of("amount", "10000.00", "currencyCode", CURRENCY),
            "idempotencyKey", payoutKey);
        var envelope = DomainEventEnvelope.of("distribution.CommissionPayoutRequested", tenantId, payload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        assertThat(disbursementRepository.findAll().stream()
            .filter(d -> payoutKey.equals(d.getIdempotencyKey()) && tenantId.equals(d.getTenantId())))
            .hasSize(1);
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    /**
     * The cross-contamination guard M6's review found missing on the claims side. {@code
     * payment.DisbursementCompleted} is one event type carrying every module's payouts, so without
     * a {@code purpose} filter distribution would try to resolve a loan id as a statement id -- or
     * worse, resolve a real one and mark a statement PAID off another module's money movement.
     */
    /**
     * The cross-contamination guard M6's review found missing on the claims side, plus a live
     * exercise of the IN_DOUBT boundary this listener's javadoc documents.
     *
     * <p>The target statement is deliberately parked at PAYOUT_REQUESTED, not PAID, and that is the
     * whole point of the setup. Against an already-PAID statement this test would pass vacuously:
     * a missing {@code purpose} filter would call {@code markPaid} on a PAID row, which is a
     * no-op that publishes nothing, so the bug would be invisible. Parked at PAYOUT_REQUESTED, a
     * dropped filter really would flip it to PAID and emit a {@code CommissionPaid} for another
     * module's money.
     *
     * <p>Parking it is done the way production actually produces that state: an indeterminate rail
     * outcome (a 5xx -> {@code GatewayException} -> IN_DOUBT) publishes NO event at all, leaving the
     * statement resting at PAYOUT_REQUESTED indefinitely.
     */
    @Test
    void aDisbursementForAnotherPurposeNeverTouchesACommissionStatement() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(serverError()));

        UUID tenantId = UUID.randomUUID();
        UUID statementId = closedStatementFor(tenantId, "OTHERPURPOSE", new BigDecimal("100000.00"));

        TenantContext.set(tenantId);
        distributionApi.requestStatementPayout(statementId, "MPESA-0713000005", "payout-" + statementId, "finance-officer");

        // Negative control, and the documented IN_DOUBT boundary: the rail said nothing, payment
        // recorded IN_DOUBT and published no event, so the statement is genuinely still awaiting a
        // confirmation that will never arrive on its own.
        assertThat(statusOf(tenantId, statementId))
            .as("an indeterminate rail outcome must leave the statement at PAYOUT_REQUESTED")
            .isEqualTo(StatementStatus.PAYOUT_REQUESTED);

        eventRecorder.clear();
        // A LOAN_DISBURSEMENT completion whose sourceRef happens to BE this statement's id -- the
        // precise shape that corrupts state if the purpose filter is ever dropped.
        publishDisbursementCompleted(tenantId, statementId, "LOAN_DISBURSEMENT", "MM-LOAN-REF");

        assertThat(statusOf(tenantId, statementId))
            .as("a LOAN_DISBURSEMENT completion must never mark a commission statement PAID")
            .isEqualTo(StatementStatus.PAYOUT_REQUESTED);
        assertThat(eventRecorder.ofType("distribution.CommissionPaid"))
            .as("a foreign-purpose disbursement must produce no commission event at all")
            .isEmpty();

        // The same guard on the failure handler.
        double failedBefore = meterRegistry.counter("lifeplatform_distribution_payout_failed_total").count();
        Map<String, Object> failedPayload = Map.of(
            "disbursementId", UUID.randomUUID(),
            "idempotencyKey", "loan-key-" + statementId,
            "sourceRef", statementId.toString(),
            "purpose", "LOAN_DISBURSEMENT",
            "reason", "INSUFFICIENT_FLOAT");
        var failedEnvelope = DomainEventEnvelope.of("payment.DisbursementFailed", tenantId, failedPayload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(failedEnvelope));

        assertThat(statusOf(tenantId, statementId))
            .as("a LOAN_DISBURSEMENT failure must never fail a commission statement")
            .isEqualTo(StatementStatus.PAYOUT_REQUESTED);
        assertThat(meterRegistry.counter("lifeplatform_distribution_payout_failed_total").count())
            .isEqualTo(failedBefore);
    }

    /** payment.DisbursementCompleted, field-for-field from PaymentApiImpl's published shape. */
    private void publishDisbursementCompleted(UUID tenantId, UUID sourceRef, String purpose, String gatewayReference) {
        Map<String, Object> payload = Map.of(
            "disbursementId", UUID.randomUUID(),
            "idempotencyKey", "redelivered-" + sourceRef,
            "sourceRef", sourceRef.toString(),
            "purpose", purpose,
            "gatewayReference", gatewayReference,
            "amount", Map.of("amount", "10000.00", "currencyCode", CURRENCY),
            "completedAt", Instant.now().toString());
        var envelope = DomainEventEnvelope.of("payment.DisbursementCompleted", tenantId, payload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));
    }
}
