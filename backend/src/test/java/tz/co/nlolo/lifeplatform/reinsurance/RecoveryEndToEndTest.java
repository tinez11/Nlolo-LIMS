package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.reinsurance.api.InvalidRecoveryStateException;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ClaimRecoveryRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * Task 7, Step 7 -- the real end-to-end proof of claim recovery: {@code ClaimsApi.decideSettlement}
 * (real API, approval branch) -> {@code claims.ClaimSettlementRequested} -> {@code
 * payment.PaymentRequestListener} -> the mobile-money rail (in-process WireMock, {@code
 * ClaimSettlementEndToEndTest}'s exact pattern) -> {@code payment.DisbursementCompleted} -> {@code
 * claims.application.PaymentEventListener} -> the M8-enriched {@code claims.ClaimSettled} -> {@code
 * reinsurance.application.ClaimEventListener} -> {@code RecoveryCalculator} -> a real {@code
 * reinsurance.claim_recovery} row -> {@code reinsurance.RecoveryCalculated} (recorded via the
 * AFTER_COMMIT {@link EventRecorder}, same pattern as {@code CessionEndToEndTest}).
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS). A claim only reaches
 * SETTLED by going through the real payment request/confirm loop, hence the WireMock rail
 * stand-in copied from {@code ClaimSettlementEndToEndTest}.
 *
 * <p>AFTER_COMMIT chains are synchronous, same thread: by the time {@code decideSettlement} returns,
 * the claim has already gone all the way through payment's confirmation and into reinsurance's
 * recovery calculation. No await/sleep anywhere in this class.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(RecoveryEndToEndTest.EventRecorderConfiguration.class)
class RecoveryEndToEndTest {

    private static final String APP_ROLE_PASSWORD = "recovery_e2e_password";
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
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    /** Records reinsurance.RecoveryCalculated/RecoveryConfirmed -- same AFTER_COMMIT pattern as
     * CessionEndToEndTest and CommissionPayoutEndToEndTest. */
    @TestConfiguration
    static class EventRecorderConfiguration {
        @Bean
        EventRecorder recoveryTestEventRecorder() { return new EventRecorder(); }
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

    @Autowired private ClaimsApi claimsApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ReinsuranceApi reinsuranceApi;
    @Autowired private ClaimRecoveryRepository claimRecoveryRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
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

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    /** Mirrors ClaimSettlementEndToEndTest.buildFixture. */
    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Recovery E2E Applicant " + productCode, LocalDate.of(1985, 3, 1),
            "+25571800" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Recovery E2E Product", ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    /** Sum assured is fixed at 2,000,000 across this class -- every scenario settles the DEATH
     * claim for the FULL sum assured, so recoverable amounts fall out of clean arithmetic. */
    private String issuePolicy(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), CURRENCY, new BigDecimal("100000.00"), CURRENCY,
            "MONTHLY", null, List.of(), "Recovery E2E test");
        String issuedPolicyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    private void createTreaty(UUID tenantId, TreatyType type, BigDecimal retention, BigDecimal cessionPercent) {
        TenantContext.set(tenantId);
        reinsuranceApi.createTreaty(new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", type, retention, CURRENCY, cessionPercent, LocalDate.now().minusMonths(1), null),
            "finance-officer");
    }

    /** Registers, assesses (for the full 2,000,000 sum assured), and returns a fresh DEATH claim
     * id, ready to approve -- mirrors ClaimSettlementEndToEndTest's helper of the same shape. */
    private UUID registerAndAssessDeathClaim(UUID tenantId, Fixture fixture, String policyNumber, String regKey, String assessor) {
        TenantContext.set(tenantId);
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, null, fixture.applicantId(),
            ClaimType.DEATH, LocalDate.now().minusDays(1),
            new DeathClaimDetails("Natural causes", "Dar es Salaam", LocalDate.now().minusDays(1), "Dr. Test"));
        UUID claimId = claimsApi.registerClaim(request, regKey, "claims-staff").claimId();
        claimsApi.submitAssessment(claimId, "Consistent with cause of death", new BigDecimal("2000000"), CURRENCY, false, assessor);
        return claimId;
    }

    /** Approves settlement for the full 2,000,000 sum assured and drives the real chain (WireMock
     * ACCEPT) all the way through payment's confirmation into claims.ClaimSettled. */
    private void settleClaim(UUID tenantId, UUID claimId, String payeeRef, String settleKey, String approver) {
        TenantContext.set(tenantId);
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), CURRENCY, null,
            payeeRef, settleKey, approver);
    }

    @Test
    void aQuotaShareCessionRecoversTheCededFractionOfTheSettledAmount() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-RECOVERY-E2E-QS\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "RECOVERY-E2E-QS");
        // 50% quota share, so the cession -- and the resulting recovery on a full-sum-assured
        // loss -- is exactly half.
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("50.00"));
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "rec-qs-reg-01", "assessor-qs-01");
        eventRecorder.clear();

        settleClaim(tenantId, claimId, "MPESA-0714000001", "rec-qs-settle-" + claimId, "manager-qs-01");

        TenantContext.set(tenantId);
        List<ClaimRecovery> recoveries = claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId);
        assertThat(recoveries).hasSize(1);
        assertThat(recoveries.get(0).getRecoverableAmount()).isEqualByComparingTo("1000000.00");
        assertThat(recoveries.get(0).getRecoverableCurrency()).isEqualTo(CURRENCY);
        assertThat(eventRecorder.ofType("reinsurance.RecoveryCalculated")).hasSize(1);

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void confirmingARecoveryStampsConfirmedAtAndARepeatConfirmThrowsWithoutASecondEvent() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-RECOVERY-E2E-CONFIRM\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "RECOVERY-E2E-CONFIRM");
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("50.00"));
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "rec-confirm-reg-01", "assessor-confirm-01");
        settleClaim(tenantId, claimId, "MPESA-0714000002", "rec-confirm-settle-" + claimId, "manager-confirm-01");

        TenantContext.set(tenantId);
        UUID recoveryId = claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId).get(0).getRecoveryId();
        eventRecorder.clear();

        TenantContext.set(tenantId);
        reinsuranceApi.confirmRecovery(recoveryId, "finance-officer-confirm-01");

        TenantContext.set(tenantId);
        ClaimRecovery confirmed = claimRecoveryRepository.findByRecoveryIdAndTenantId(recoveryId, tenantId).orElseThrow();
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        assertThat(eventRecorder.ofType("reinsurance.RecoveryConfirmed")).hasSize(1);

        TenantContext.set(tenantId);
        assertThrows(InvalidRecoveryStateException.class,
            () -> reinsuranceApi.confirmRecovery(recoveryId, "finance-officer-confirm-01"));
        assertThat(eventRecorder.ofType("reinsurance.RecoveryConfirmed"))
            .as("a repeat confirmRecovery must not publish a second RecoveryConfirmed")
            .hasSize(1);
    }

    @Test
    void anXolTreatyWithRetentionBelowTheSettledAmountRecoversTheExcessWithNoCession() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-RECOVERY-E2E-XOL-BELOW\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "RECOVERY-E2E-XOL-BELOW");
        createTreaty(tenantId, TreatyType.XOL, new BigDecimal("1500000.00"), null);
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "rec-xol-below-reg-01", "assessor-xol-below-01");
        eventRecorder.clear();

        settleClaim(tenantId, claimId, "MPESA-0714000003", "rec-xol-below-settle-" + claimId, "manager-xol-below-01");

        TenantContext.set(tenantId);
        List<ClaimRecovery> recoveries = claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId);
        assertThat(recoveries).hasSize(1);
        // 2,000,000 settled - 1,500,000 retention = 500,000 excess, even though XOL ceded nothing
        // at issuance and therefore has no cession row at all.
        assertThat(recoveries.get(0).getRecoverableAmount()).isEqualByComparingTo("500000.00");
        assertThat(eventRecorder.ofType("reinsurance.RecoveryCalculated")).hasSize(1);
    }

    @Test
    void anXolTreatyWithRetentionAboveTheSettledAmountRecoversNothing() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-RECOVERY-E2E-XOL-ABOVE\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "RECOVERY-E2E-XOL-ABOVE");
        createTreaty(tenantId, TreatyType.XOL, new BigDecimal("3000000.00"), null);
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "rec-xol-above-reg-01", "assessor-xol-above-01");
        eventRecorder.clear();

        settleClaim(tenantId, claimId, "MPESA-0714000004", "rec-xol-above-settle-" + claimId, "manager-xol-above-01");

        TenantContext.set(tenantId);
        assertThat(claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId)).isEmpty();
        assertThat(eventRecorder.ofType("reinsurance.RecoveryCalculated")).isEmpty();
    }

    /**
     * A claim settling on a policy reinsurance never learned about (no {@code policy_projection}
     * row) must recover nothing and must not let any exception escape -- the "pre-M8 policy" case
     * {@code ClaimEventListener}'s own log message names. Publishing {@code claims.ClaimSettled}
     * directly (rather than through the real claims/payment chain) is deliberate and sufficient:
     * {@code ClaimEventListener} never queries `claims` or `policy` at all (neither is in this
     * module's {@code allowedDependencies}), so a policy number that never went through {@code
     * PolicyEventListener} is exactly what "no projection row" means, with or without a real Claim
     * row backing it.
     */
    @Test
    void aClaimSettledOnAPolicyWithNoProjectionRowRecoversNothingAndThrowsNoException() {
        UUID tenantId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        String neverIssuedPolicyNumber = "POL-NEVER-ISSUED-" + claimId.toString().substring(0, 8).toUpperCase();
        eventRecorder.clear();

        Map<String, Object> payload = Map.of(
            "claimId", claimId,
            "policyNumber", neverIssuedPolicyNumber,
            "settledAmount", Map.of("amount", "2000000", "currencyCode", CURRENCY),
            "settledAt", java.time.Instant.now().toString());
        var envelope = DomainEventEnvelope.of("claims.ClaimSettled", tenantId, payload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        TenantContext.set(tenantId);
        assertThat(claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId)).isEmpty();
        assertThat(eventRecorder.ofType("reinsurance.RecoveryCalculated")).isEmpty();
    }

    /**
     * A real at-least-once redelivery of the M8-enriched {@code claims.ClaimSettled} must still
     * leave exactly one recovery row -- backstopped by {@code ux_recovery_once} and made a
     * no-op earlier by {@code ReinsuranceApiImpl.persistRecovery}'s existence check -- and publish
     * no second {@code RecoveryCalculated}.
     */
    @Test
    void aRedeliveredClaimSettledStillLeavesExactlyOneRecoveryAndPublishesNoSecondEvent() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-RECOVERY-E2E-REDELIVER\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "RECOVERY-E2E-REDELIVER");
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("50.00"));
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "rec-redeliver-reg-01", "assessor-redeliver-01");
        eventRecorder.clear();

        settleClaim(tenantId, claimId, "MPESA-0714000005", "rec-redeliver-settle-" + claimId, "manager-redeliver-01");
        TenantContext.set(tenantId);
        assertThat(claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId)).hasSize(1);
        assertThat(eventRecorder.ofType("reinsurance.RecoveryCalculated")).hasSize(1);

        // claims.ClaimSettled, field-for-field from PaymentEventListener's M8-enriched published
        // shape -- only the fields ClaimEventListener actually reads.
        Map<String, Object> payload = Map.of(
            "claimId", claimId,
            "policyNumber", policyNumber,
            "settledAmount", Map.of("amount", "2000000", "currencyCode", CURRENCY),
            "settledAt", java.time.Instant.now().toString());
        var envelope = DomainEventEnvelope.of("claims.ClaimSettled", tenantId, payload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        TenantContext.set(tenantId);
        assertThat(claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId))
            .as("a redelivered ClaimSettled must not double-recover")
            .hasSize(1);
        assertThat(eventRecorder.ofType("reinsurance.RecoveryCalculated"))
            .as("a redelivered ClaimSettled must not publish a second RecoveryCalculated")
            .hasSize(1);
    }
}
