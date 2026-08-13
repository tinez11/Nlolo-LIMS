package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.MaturityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.payment.domain.DisbursementInstruction;
import tz.co.nlolo.lifeplatform.payment.infrastructure.DisbursementInstructionRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 6, Step 4 -- the real end-to-end proof that claims' settlement request actually reaches
 * {@code payment}: {@code claimsApi.decideSettlement} (real API, approval branch) ->
 * {@code claims.ClaimSettlementRequested} (real event, published by ClaimsApiImpl -- Task 5) ->
 * {@code payment.PaymentRequestListener.handleClaimSettlement} (this task's new branch) -> the
 * mobile-money gateway (in-process WireMock stand-in, same pattern as
 * {@code PaymentRequestListenerIntegrationTest}/{@code LoanDisbursementEndToEndTest}) ->
 * {@code payment.disbursement_instruction} row with {@code purpose = 'CLAIM_SETTLEMENT'}.
 *
 * <p>Task 7 extends this class to close the final hop: {@code payment.DisbursementCompleted}/
 * {@code DisbursementFailed} -> {@code claims.application.PaymentEventListener} -> the claim
 * reaches {@code SETTLED} (publishing {@code claims.ClaimSettled}, verified via the real
 * {@code audit.audit_log} row -- the same idiom {@code PolicyClaimClosureTest} and
 * {@code PaymentRequestListenerIntegrationTest} already use) or reverts to {@code APPROVED}, and
 * -- ONLY on a genuine settlement -- the underlying policy leaves {@code ACTIVE}.
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS), copying
 * {@code ClaimsApiIntegrationTest}'s bootstrap, so RLS on both {@code claims.claim} and
 * {@code payment.disbursement_instruction} is genuinely exercised end to end, not merely
 * declared -- and copying {@code PaymentRequestListenerIntegrationTest}'s WireMock setup for the
 * mobile-money rail stand-in.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} chains are synchronous, same
 * thread: {@code decideSettlement}'s own {@code @Transactional} commits when the method returns
 * (before control comes back to this test), firing payment's listener synchronously; that
 * listener's own REQUIRES_NEW transactions (record request, call the gateway, complete-or-fail)
 * commit before the call even returns to {@code decideSettlement}'s caller. No await/sleep
 * anywhere in this class.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ClaimSettlementEndToEndTest {

    private static final String APP_ROLE_PASSWORD = "claims_settlement_e2e_password";

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
        // Mirrors ClaimsApiIntegrationTest's migration list (the claims registration/decision
        // chain's full dependency set) plus payment/V1 and payment/V2 -- this is the one test
        // class in the suite that needs claims' AND payment's schemas live at once.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
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

    @Autowired private ClaimsApi claimsApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private DisbursementInstructionRepository disbursementRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClaimRepository claimRepository;

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

    /** Mirrors ClaimsApiIntegrationTest.buildFixture exactly. */
    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Claims E2E Applicant " + productCode, LocalDate.of(1985, 3, 1),
            "+25571600" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Claims E2E Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issuePolicyWithNullUnderwritingCase(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("2000000"), "TZS", new BigDecimal("40000.00"), "TZS", "MONTHLY", null, List.of(), "Claims E2E test");
        return policyApi.issuePolicy(null, request, "test-staff").policyNumber();
    }

    /** Registers, assesses, and returns a fresh DEATH claim id, ready to approve. */
    private UUID registerAndAssessDeathClaim(UUID tenantId, Fixture fixture, String policyNumber, String regKey, String assessor) {
        TenantContext.set(tenantId);
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, fixture.applicantId(),
            ClaimType.DEATH, LocalDate.now().minusDays(1),
            new DeathClaimDetails("Natural causes", "Dar es Salaam", LocalDate.now().minusDays(1), "Dr. Test"));
        UUID claimId = claimsApi.registerClaim(request, regKey, "claims-staff").claimId();
        claimsApi.submitAssessment(claimId, "Consistent with cause of death", new BigDecimal("2000000"), "TZS", false, assessor);
        return claimId;
    }

    /** Registers a fresh MATURITY claim, ready to approve without any assessment -- MATURITY
     * auto-approves REGISTERED -> APPROVED (Claim.approve, Cl3), unlike DEATH. */
    private UUID registerMaturityClaim(UUID tenantId, Fixture fixture, String policyNumber, String regKey) {
        TenantContext.set(tenantId);
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, fixture.applicantId(),
            ClaimType.MATURITY, LocalDate.now(), new MaturityClaimDetails(LocalDate.now()));
        return claimsApi.registerClaim(request, regKey, "claims-staff").claimId();
    }

    @Test
    void approvingAClaimRunsTheRealChainThroughToARealDisbursementInstructionRow() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CLAIM-E2E-SUCCESS\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-SUCCESS");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "e2e-reg-01", "assessor-e2e-01");
        String settleKey = "e2e-settle-" + claimId;

        // 1. Approve over the real API -- decideSettlement's own @Transactional commits when this
        // call returns, firing payment's AFTER_COMMIT listener synchronously before control comes
        // back here.
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "MPESA-0712000001", settleKey, "manager-e2e-01");

        // 2. Task 7 wires claims.application.PaymentEventListener into this same AFTER_COMMIT
        // chain, so by the time decideSettlement returns, the claim has already gone all the way
        // to SETTLED -- not merely SETTLEMENT_REQUESTED as it did before this task existed.
        ClaimView view = claimsApi.getClaim(claimId);
        assertThat(view.status()).isEqualTo(ClaimStatus.SETTLED);

        // 3. A REAL payment.disbursement_instruction row exists, with purpose=CLAIM_SETTLEMENT
        // and source_ref=claimId -- not merely "some row", the specific correlation Task 7 needs.
        DisbursementInstruction instruction = disbursementRepository
            .findByIdempotencyKeyAndTenantId(settleKey, tenantId)
            .orElseThrow(() -> new AssertionError("Expected a disbursement_instruction row for key " + settleKey));
        assertThat(instruction.getPurpose()).isEqualTo("CLAIM_SETTLEMENT");
        assertThat(instruction.getSourceRef()).isEqualTo(claimId.toString());
        assertThat(instruction.getStatus()).isEqualTo("COMPLETED");
        assertThat(instruction.getGatewayReference()).isEqualTo("MM-CLAIM-E2E-SUCCESS");
        assertThat(instruction.getPayeeRef()).isEqualTo("MPESA-0712000001");
        assertThat(instruction.getAmount()).isEqualByComparingTo("2000000");

        // 4. The gateway was called exactly once -- the real proof the rail genuinely ran.
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void republishingTheSameSettlementEventWithTheSameKeyNeverReachesTheRailAgain() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CLAIM-E2E-ONCE\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-DUP");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "e2e-reg-02", "assessor-e2e-02");
        String settleKey = "e2e-settle-" + claimId;

        // First approval: the real path, reaching the rail once.
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "MPESA-0712000002", settleKey, "manager-e2e-02");
        assertThat(disbursementRepository.findByIdempotencyKeyAndTenantId(settleKey, tenantId)).isPresent();
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));

        // A real at-least-once redelivery of the SAME claims.ClaimSettlementRequested envelope
        // (same tenantId, same idempotencyKey) -- not a second call through claimsApi, which
        // cannot legally re-approve an already-approved claim. This is the M5 lesson made
        // concrete for a new producer: the registry claim inside payment's own
        // recordDisbursementRequest dedupes it before the gateway is ever called again.
        var envelope = tz.co.nlolo.lifeplatform.DomainEventEnvelope.of("claims.ClaimSettlementRequested", tenantId,
            java.util.Map.of("claimId", claimId, "payeeRef", "MPESA-0712000002",
                "amount", java.util.Map.of("amount", "2000000", "currencyCode", "TZS"),
                "idempotencyKey", settleKey));
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        // Still exactly one row, and the rail was hit zero ADDITIONAL times.
        assertThat(disbursementRepository.findAll().stream()
            .filter(d -> settleKey.equals(d.getIdempotencyKey()) && tenantId.equals(d.getTenantId())))
            .hasSize(1);
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void aBlankIdempotencyKeyIsRejectedAtTheClaimsBoundaryNotSwallowedInsidePayment() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-BLANKKEY");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "e2e-reg-03", "assessor-e2e-03");

        // decideSettlement (Task 5) must reject a blank idempotencyKey BEFORE ever publishing
        // claims.ClaimSettlementRequested -- so this must never even reach the rail, and the
        // claim must never leave APPROVED-in-progress for SETTLEMENT_REQUESTED.
        assertThrows(ClaimValidationException.class, () -> claimsApi.decideSettlement(claimId, true,
            new BigDecimal("2000000"), "TZS", null, "MPESA-0712000003", "   ", "manager-e2e-03"));

        assertThat(claimsApi.getClaim(claimId).status())
            .as("a rejected settlement decision must not leave the claim at SETTLEMENT_REQUESTED")
            .isNotEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);

        wireMock.verify(exactly(0), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    /**
     * Task 7's central proof: the FULL real chain, not just to SETTLEMENT_REQUESTED as the tests
     * above stop at, but all the way through payment's confirmation back into claims and out to
     * policy. Approve (real API) -> claims.ClaimSettlementRequested (real event) -> payment
     * requests a disbursement against the real (WireMock) rail -> the rail ACCEPTs ->
     * payment.DisbursementCompleted (real event) -> claims.application.PaymentEventListener ->
     * Claim.markSettled() -> claims.ClaimSettled (real event, verified via the real
     * audit.audit_log row) -> PolicyApi.terminateForSettledClaim (DEATH is not MATURITY) ->
     * policy.status read back from the database == SURRENDERED.
     */
    @Test
    void aSuccessfulDisbursementSettlesTheDeathClaimAndSurrendersThePolicy() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CLAIM-E2E-SETTLE-DEATH\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-SETTLE-DEATH");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "e2e-settle-reg-01", "assessor-settle-01");
        String settleKey = "e2e-settle-death-" + claimId;

        Instant before = Instant.now();
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "MPESA-0712000010", settleKey, "manager-settle-01");

        // The claim reached SETTLED, not merely SETTLEMENT_REQUESTED -- the hop this task adds.
        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);

        // claims.ClaimSettled was really published -- read back from the real audit_log row, not
        // assumed from the claim's own state.
        List<AuditLogEntry> settledRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "claims.ClaimSettled", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(settledRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(settledRows.get(0).getPayload());
        assertThat(payload.path("claimId").asText()).isEqualTo(claimId.toString());

        // The falsifiable proof this milestone's user decision closed a real bug: the policy left
        // ACTIVE. DEATH is not MATURITY, so the policy must be SURRENDERED, not MATURED -- read
        // back from the database via the real published API, not inferred from any event.
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.SURRENDERED);

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    /** Same chain as above, but for a MATURITY claim (auto-approved, no assessment) -- exercises
     * the OTHER branch of PaymentEventListener.handleCompleted's claim-type dispatch, proving
     * markMatured (not terminateForSettledClaim) is the one actually called for this claim type. */
    @Test
    void aSuccessfulDisbursementSettlesTheMaturityClaimAndMaturesThePolicy() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CLAIM-E2E-SETTLE-MATURITY\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-SETTLE-MATURITY");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerMaturityClaim(tenantId, fixture, policyNumber, "e2e-maturity-reg-01");
        String settleKey = "e2e-settle-maturity-" + claimId;

        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "MPESA-0712000011", settleKey, "manager-settle-02");

        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.MATURED);

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    /**
     * The negative half of Task 7's central proof, and the one that would catch handleFailed
     * ever being miswired to also close the policy: a genuine rail DECLINE (not a timeout/
     * indeterminate outcome) must revert the claim to APPROVED with the reason recorded, and must
     * leave the policy untouched at ACTIVE.
     */
    @Test
    void aFailedDisbursementRevertsTheClaimToApprovedAndLeavesThePolicyActive() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_FLOAT\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-SETTLE-FAIL");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "e2e-fail-reg-01", "assessor-fail-01");
        String settleKey = "e2e-settle-fail-" + claimId;

        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "MPESA-0712000012", settleKey, "manager-fail-01");

        // Reverted, not settled: SETTLEMENT_REQUESTED -> APPROVED, reason preserved for the staff
        // retry worklist. settlementFailureReason is not on the published ClaimView (Task 4's
        // fixed field set) so this reads the aggregate directly, same idiom ClaimStateMachineTest
        // already uses.
        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.APPROVED);
        Claim claim = claimRepository.findByClaimIdAndTenantId(claimId, tenantId)
            .orElseThrow(() -> new AssertionError("Expected claim " + claimId + " to exist"));
        assertThat(claim.getSettlementFailureReason()).isEqualTo("INSUFFICIENT_FLOAT");

        // The danger this test exists to catch: handleFailed must NEVER touch the policy. Still
        // ACTIVE, read back from the database via the real published API.
        assertThat(policyApi.getPolicy(policyNumber).status())
            .as("a failed disbursement must not close the policy")
            .isEqualTo(PolicyStatus.ACTIVE);

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }
}
