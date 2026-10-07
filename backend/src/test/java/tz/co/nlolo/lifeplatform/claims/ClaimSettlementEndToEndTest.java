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
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.GroupSchemeView;
import tz.co.nlolo.lifeplatform.policy.api.MemberStatus;
import tz.co.nlolo.lifeplatform.policy.api.IssuanceBasis;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyMemberView;
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

import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

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
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
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
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
            "db-migrations/product/V29__funeral_group_rate.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/underwriting/V19__group_funeral_proposal.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V8__group_policies_have_no_single_life_assured.sql",
            "db-migrations/policy/V9__group_scheme_and_members.sql",
            "db-migrations/policy/V13__freeform_members.sql",
            "db-migrations/policy/V14__credit_life_scheme.sql",
            "db-migrations/policy/V15__enrolment_submission.sql",
            "db-migrations/policy/V16__insurer_issued_member_reference.sql",
            "db-migrations/policy/V18__scheme_premium_rate.sql",
            "db-migrations/policy/V19__enrolment_premium.sql",
            "db-migrations/policy/V20__member_exit_reason.sql",
            "db-migrations/policy/V22__member_promoted_party.sql",
            "db-migrations/policy/V23__member_open_death_claim.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V25__credit_life_premium_basis.sql",
            "db-migrations/policy/V26__enrolment_stated_premium.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/policy/V37__sale_classification.sql",
            "db-migrations/policy/V38__group_funeral_scheme.sql",
            "db-migrations/policy/V40__commencement_never_null.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql",
            "db-migrations/payment/V8__benefit_payout_purposes.sql",
            "db-migrations/payment/V9__account_purposes.sql");
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
    /** For the C1 part-3 assertion that the policy-closure failure is genuinely alertable. */
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            // DEATH and MATURITY: this class claims against both, and a claim is now valued
            // against the benefit its product authored. Both at SUM_ASSURED, so every
            // existing amount assertion is unchanged.
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.MATURITY, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issuePolicyWithNullUnderwritingCase(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        // Cover from a month ago: the claims below date the death yesterday, and a policy issued today with no start
        // date of its own covers nothing before today (audit 2026-10-07 -- this fixture had relied on there being no
        // lower bound at all).
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("2000000"), "TZS", new BigDecimal("40000.00"), "TZS", "MONTHLY", null, List.of(), "Claims E2E test",
            LocalDate.now().minusMonths(1), null, null, null, null);
        String issuedPolicyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    /**
     * A policy that has reached its maturity date today: commenced a year ago on a 12-month term.
     * A maturity claim is only claimable once the term is up, which registration now enforces.
     */
    private String issueMaturedPolicy(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("2000000"), "TZS", new BigDecimal("40000.00"), "TZS", "MONTHLY", null, List.of(), "Claims E2E test",
            LocalDate.now().minusMonths(12), 12, null, null, null);
        String issuedPolicyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    /** Registers, assesses, and returns a fresh DEATH claim id, ready to approve. */
    private UUID registerAndAssessDeathClaim(UUID tenantId, Fixture fixture, String policyNumber, String regKey, String assessor) {
        TenantContext.set(tenantId);
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, null, fixture.applicantId(),
            ClaimType.DEATH, LocalDate.now().minusDays(1),
            new DeathClaimDetails("Natural causes", "Dar es Salaam", LocalDate.now().minusDays(1), "Dr. Test"));
        UUID claimId = claimsApi.registerClaim(request, regKey, "claims-staff").claimId();
        claimsApi.submitAssessment(claimId, "Consistent with cause of death", new BigDecimal("2000000"), "TZS", false, assessor, null);
        return claimId;
    }

    /** Registers a fresh MATURITY claim, ready to approve without any assessment -- MATURITY
     * auto-approves REGISTERED -> APPROVED (Claim.approve, Cl3), unlike DEATH. */
    private UUID registerMaturityClaim(UUID tenantId, Fixture fixture, String policyNumber, String regKey) {
        TenantContext.set(tenantId);
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, null, fixture.applicantId(),
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

    /**
     * The group counterpart, and the one that pays the right money to the right contract.
     *
     * <p>Same rail as the individual case above and a completely different outcome: a settled
     * member claim discharges ONE LIFE. The deceased leaves the schedule dated to the event, the
     * scheme's sum assured drops to what the survivors are covered for, and the master policy
     * stays in force so the other members keep their cover and the employer keeps being invoiced.
     *
     * <p>Before this, the policy went SURRENDERED and 499 people were silently uninsured.
     */
    @Test
    void aSettledMemberClaimExitsThatLifeAndLeavesTheSchemeInForce() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CLAIM-E2E-GROUP\"}")));

        UUID tenantId = UUID.randomUUID();
        // Six months back, so "exited on the date of event" is distinguishable from "exited on
        // the day the payment cleared" -- with an event today the two dates coincide and the
        // assertion below would pass for an implementation doing the wrong one.
        LocalDate dateOfEvent = LocalDate.now().minusMonths(6);
        GroupFixture scheme = issueGroupSchemeOfTwo(tenantId, "CLAIMS-E2E-GROUP", dateOfEvent);
        UUID deceased = scheme.memberIdNamed("Juma Deceased");

        assertThat(policyApi.getGroupScheme(scheme.policyNumber()).totalCoveredAmount())
            .isEqualByComparingTo(new BigDecimal("10000000.00"));

        TenantContext.set(tenantId);
        ClaimView claim = claimsApi.registerClaim(new ClaimsApi.RegisterClaimRequest(
            scheme.policyNumber(), deceased, scheme.employerPartyId(), ClaimType.DEATH, dateOfEvent,
            new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfEvent, "Dr. Test")),
            "e2e-group-reg-01", "clerk");
        claimsApi.submitAssessment(claim.claimId(), "Findings", new BigDecimal("5000000.00"),
            "TZS", false, "assessor-group-01", null);
        claimsApi.decideSettlement(claim.claimId(), true, new BigDecimal("5000000.00"), "TZS", null,
            "MPESA-0712000099", "e2e-group-settle-01", "manager-group-01");

        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claim.claimId()).status()).isEqualTo(ClaimStatus.SETTLED);

        // THE ASSERTION THIS WHOLE CHANGE EXISTS FOR.
        assertThat(policyApi.getPolicy(scheme.policyNumber()).status())
            .as("499 other people are still insured")
            .isEqualTo(PolicyStatus.ACTIVE);

        // The deceased is off the roll, dated to the event rather than to the payment run.
        PolicyMemberView exited = memberById(scheme.policyNumber(), deceased);
        assertThat(exited.status()).isEqualTo(MemberStatus.EXITED);
        assertThat(exited.leftOn()).isEqualTo(dateOfEvent);

        // And the contract total is now what the survivor alone is covered for.
        assertThat(policyApi.getGroupScheme(scheme.policyNumber()).totalCoveredAmount())
            .as("a dead member must stop contributing to the scheme's sum assured")
            .isEqualByComparingTo(new BigDecimal("5000000.00"));
        assertThat(memberById(scheme.policyNumber(), scheme.memberIdNamed("Asha Living")).status())
            .isEqualTo(MemberStatus.ACTIVE);
    }

    private record GroupFixture(String policyNumber, UUID employerPartyId, Map<String, UUID> membersByName) {
        UUID memberIdNamed(String name) {
            UUID id = membersByName.get(name);
            if (id == null) throw new AssertionError("No member named " + name + " on " + policyNumber);
            return id;
        }
    }

    /** Two lives at 5,000,000 each, commenced before {@code dateOfEvent} so both were covered then. */
    private GroupFixture issueGroupSchemeOfTwo(UUID tenantId, String productCode, LocalDate dateOfEvent) {
        TenantContext.set(tenantId);
        ProductSummaryView product = productApi.createProduct(productCode, "Group Life " + productCode,
            ProductCategory.GROUP_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            // DEATH and MATURITY: this class claims against both, and a claim is now valued
            // against the benefit its product authored. Both at SUM_ASSURED, so every
            // existing amount assertion is unchanged.
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.MATURITY, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        UUID employer = partyApi.registerIndividual("ABC Company " + productCode, LocalDate.of(1985, 3, 1),
            "+25571700" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent").partyId();
        UUID first = partyApi.registerIndividual("Juma Deceased " + productCode, LocalDate.of(1985, 3, 1),
            "+25571800" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent").partyId();
        UUID second = partyApi.registerIndividual("Asha Living " + productCode, LocalDate.of(1985, 3, 1),
            "+25571900" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent").partyId();

        GroupSchemeView scheme = policyApi.issueGroupScheme(new PolicyApi.IssueGroupSchemeRequest(
            employer, product.productId(), versionId, null,
            BenefitBasis.FLAT, new BigDecimal("5000000.00"), null, null, "TZS", null,
            List.of(new PolicyApi.MemberInput(first, null, null, null),
                    new PolicyApi.MemberInput(second, null, null, null)),
            new BigDecimal("1200000.00"), "TZS", "ANNUALLY", dateOfEvent.minusMonths(1), null,
            "group onboarding", IssuanceBasis.MIGRATION), "staff1");

        Map<UUID, String> nameByParty = Map.of(first, "Juma Deceased", second, "Asha Living");
        Map<String, UUID> byName = policyApi
            .listMembers(scheme.policyNumber(), null, null, PageRequest.of(0, 25))
            .getContent().stream()
            .collect(Collectors.toMap(m -> nameByParty.get(m.memberPartyId()),
                                       PolicyMemberView::policyMemberId));
        return new GroupFixture(scheme.policyNumber(), employer, byName);
    }

    private PolicyMemberView memberById(String policyNumber, UUID policyMemberId) {
        return policyApi.listMembers(policyNumber, null, null, PageRequest.of(0, 25))
            .getContent().stream()
            .filter(m -> m.policyMemberId().equals(policyMemberId))
            .findFirst().orElseThrow(() -> new AssertionError("Member " + policyMemberId + " vanished"));
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
        // A maturity claim is only claimable on a policy that has reached its maturity date, which
        // the registration now enforces. This fixture matures today.
        String policyNumber = issueMaturedPolicy(tenantId, fixture);
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

    // ============================================================================================
    // M6 final-review fix wave: C1 (a settled claim on a non-ACTIVE policy), I1 (duplicate
    // ClaimSettled on redelivery), I2 (a re-decided amount diverging from the record), and the
    // purpose-filter cross-contamination guard.
    // ============================================================================================

    /**
     * <b>The direct regression guard for the Critical (C1).</b> A death claim whose policy has since
     * LAPSED -- which happens unattended, because {@code PolicyLapseRecommendedEventListener} lapses
     * a policy on {@code billing.PolicyLapseRecommended} at dunning level >= 5 and a deceased
     * policyholder stops paying premiums -- must settle normally: the claim genuinely reaches
     * SETTLED and the policy genuinely reaches SURRENDERED.
     *
     * <p>Before the fix, {@code Policy.terminateForSettledClaim()} threw for a LAPSED source state
     * from inside {@code PaymentEventListener.handleCompleted}'s single transaction, which rolled
     * back the claim's OWN SETTLED transition after the disbursement had already COMPLETED: money
     * gone, claim wedged back at SETTLEMENT_REQUESTED with a NULL failure reason, no ClaimSettled
     * event, and no way back out through any existing transition. Both halves are asserted below --
     * the claim (proving the transition was not reverted) and the policy (proving the widened guard
     * actually accepts LAPSED rather than the claim merely surviving a swallowed failure).
     */
    @Test
    void aSettledDeathClaimClosesAPolicyThatHasSinceLapsed() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CLAIM-E2E-LAPSED\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-LAPSED");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        // Registered while the policy is still ACTIVE (registerClaim's isPolicyInForce check would
        // otherwise 422 -- see ClaimsApiImpl's step 2 comment on that known policy gap, I3), then
        // lapsed the way dunning would while the claim was still being assessed.
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "e2e-lapsed-reg-01", "assessor-lapsed-01");
        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "billing-dunning-simulated");
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.LAPSED);

        String settleKey = "e2e-settle-lapsed-" + claimId;
        Instant before = Instant.now();
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "MPESA-0712000020", settleKey, "manager-lapsed-01");

        // 1. The claim really reached SETTLED and STAYED there -- read back from the database, which
        //    is where the rollback used to be visible.
        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status())
            .as("a settled claim must never be rolled back by a policy precondition")
            .isEqualTo(ClaimStatus.SETTLED);
        Claim claim = claimRepository.findByClaimIdAndTenantId(claimId, tenantId).orElseThrow();
        assertThat(claim.getSettlementFailureReason()).isNull();

        // 2. claims.ClaimSettled was really published (it was not, before the fix).
        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "claims.ClaimSettled", before.minusSeconds(5), Instant.now().plusSeconds(5)))
            .hasSize(1);

        // 3. The money really moved, which is what makes the claim's SETTLED state non-negotiable.
        DisbursementInstruction instruction = disbursementRepository
            .findByIdempotencyKeyAndTenantId(settleKey, tenantId).orElseThrow();
        assertThat(instruction.getStatus()).isEqualTo("COMPLETED");
        assertThat(instruction.getGatewayReference()).isEqualTo("MM-CLAIM-E2E-LAPSED");

        // 4. And the LAPSED policy was genuinely closed, not left for billing to keep invoicing.
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.SURRENDERED);
    }

    /**
     * The other half of C1: the phase separation itself, proven against a policy-closure failure
     * that genuinely throws. With the widened guards, every state a policy can legitimately be in
     * when a claim settles (ACTIVE, REINSTATED, LAPSED, SUSPENDED, or already closed) now succeeds,
     * so this test manufactures the residual case -- a PROPOSED policy, which
     * {@code Policy.terminateForSettledClaim()} still rejects on purpose -- by writing the status
     * directly with a superuser connection. That is deliberate: the point is not that PROPOSED is
     * reachable in production, it is that <b>ANY</b> failure of the policy call must leave the
     * claim's settled-and-paid fact intact and raise an alertable signal instead.
     *
     * <p>Asserts all three parts of the fix at once: the claim stays SETTLED (phase 1 committed
     * independently), {@code claims.ClaimSettled} was still published, and
     * {@code lifeplatform_claims_policy_closure_failed_total} incremented (the residual case is
     * visible, not swallowed into a single ERROR line).
     */
    @Test
    void aPolicyClosureFailureLeavesTheClaimSettledAndIncrementsTheAlertableCounter() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CLAIM-E2E-CLOSEFAIL\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-CLOSEFAIL");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "e2e-closefail-reg-01", "assessor-closefail-01");

        // Force the one source state the closure guard still refuses. Done over a superuser
        // connection (not the app's app_role datasource) because no published API can put an issued
        // policy back to PROPOSED -- by design.
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE policy.policy SET status = 'PROPOSED' WHERE policy_number = '" + policyNumber + "'");
        }

        double closureFailuresBefore = policyClosureFailureCount();
        Instant before = Instant.now();
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "MPESA-0712000021", "e2e-settle-closefail-" + claimId, "manager-closefail-01");

        // The claim is SETTLED and final -- the disbursement COMPLETED, so this is the only correct
        // outcome. This is the assertion that fails if the two phases are ever merged back into one
        // transaction: the InvalidPolicyStateException would unwind this transition too.
        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "claims.ClaimSettled", before.minusSeconds(5), Instant.now().plusSeconds(5)))
            .hasSize(1);
        assertThat(disbursementRepository.findByIdempotencyKeyAndTenantId("e2e-settle-closefail-" + claimId, tenantId)
            .orElseThrow().getStatus()).isEqualTo("COMPLETED");

        // The policy really was left unclosed -- so the counter is the ONLY signal, and it fired.
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.PROPOSED);
        assertThat(policyClosureFailureCount())
            .as("the residual case must be alertable, not silent")
            .isEqualTo(closureFailuresBefore + 1);
    }

    private double policyClosureFailureCount() {
        io.micrometer.core.instrument.Counter counter =
            meterRegistry.find("lifeplatform_claims_policy_closure_failed_total").counter();
        return counter == null ? 0d : counter.count();
    }

    /**
     * I1: a redelivered {@code payment.DisbursementCompleted} must publish exactly ONE
     * {@code claims.ClaimSettled}. {@code Claim.markSettled()} was already idempotent, but the
     * publish ran unconditionally after it, so the audit rows went 1 -> 2 on republishing the same
     * envelope. Declared consumers are finaccounting (journal posting), communication and
     * regreporting -- a duplicate journal entry is the version of this bug that costs money.
     */
    @Test
    void aRedeliveredDisbursementCompletedPublishesExactlyOneClaimSettled() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CLAIM-E2E-REDELIVER\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-REDELIVER");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "e2e-redeliver-reg-01", "assessor-redeliver-01");
        String settleKey = "e2e-settle-redeliver-" + claimId;

        Instant before = Instant.now();
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "MPESA-0712000022", settleKey, "manager-redeliver-01");
        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);

        // A real at-least-once redelivery of payment's OWN DisbursementCompleted envelope, built
        // field-for-field from PaymentApiImpl.completeDisbursement's published payload (not an
        // invented shape) using the real disbursement row this settlement just produced.
        DisbursementInstruction instruction = disbursementRepository
            .findByIdempotencyKeyAndTenantId(settleKey, tenantId).orElseThrow();
        var envelope = tz.co.nlolo.lifeplatform.DomainEventEnvelope.of("payment.DisbursementCompleted", tenantId,
            java.util.Map.of("disbursementId", instruction.getDisbursementId(),
                "idempotencyKey", settleKey,
                "sourceRef", claimId.toString(),
                "purpose", "CLAIM_SETTLEMENT",
                "gatewayReference", "MM-CLAIM-E2E-REDELIVER",
                "amount", java.util.Map.of("amount", "2000000", "currencyCode", "TZS"),
                "completedAt", Instant.now().toString()));
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        // Exactly one ClaimSettled across BOTH deliveries -- two before the fix.
        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "claims.ClaimSettled", before.minusSeconds(5), Instant.now().plusSeconds(5)))
            .hasSize(1);
        // Exactly one PolicySurrendered too: the redelivery skips the policy call as well, keeping
        // both idempotent halves of handleCompleted consistent with each other.
        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "policy.PolicySurrendered", before.minusSeconds(5), Instant.now().plusSeconds(5)))
            .hasSize(1);
        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);
    }

    /**
     * The purpose-filter cross-contamination guard (escalation-list item, folded into this wave).
     * {@code payment.DisbursementCompleted} is a shared event type: policyloan's loan payouts ride it
     * too. Both listeners filter on {@code purpose}, and both are correct today -- nothing would
     * catch a future regression where claims' listener stopped filtering and started settling claims
     * off another module's payout.
     *
     * <p>Non-vacuous by construction: the claim is parked at SETTLEMENT_REQUESTED (the ONE status
     * from which {@code markSettled()} would actually succeed), so if the {@code purpose} check were
     * removed this event WOULD settle it and close the policy. Parking it there uses the real
     * indeterminate path -- a 500 from the rail is a {@code GatewayException}, which payment records
     * as IN_DOUBT and deliberately publishes no event for, leaving the claim exactly where a
     * genuinely unknown outcome leaves it.
     */
    @Test
    void aLoanDisbursementCompletedNeverTouchesAClaimAwaitingSettlement() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(aResponse().withStatus(500)));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-PURPOSE");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "e2e-purpose-reg-01", "assessor-purpose-01");

        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "MPESA-0712000023", "e2e-settle-purpose-" + claimId, "manager-purpose-01");
        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status())
            .as("an indeterminate rail outcome must leave the claim awaiting settlement")
            .isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);

        // Another module's payout, riding the same event type, carrying THIS claim's id as its
        // sourceRef -- the worst case, and still not claims' business.
        var envelope = tz.co.nlolo.lifeplatform.DomainEventEnvelope.of("payment.DisbursementCompleted", tenantId,
            java.util.Map.of("disbursementId", UUID.randomUUID(),
                "idempotencyKey", "e2e-purpose-loan-" + claimId,
                "sourceRef", claimId.toString(),
                "purpose", "LOAN_DISBURSEMENT",
                "gatewayReference", "MM-LOAN-NOT-A-CLAIM",
                "amount", java.util.Map.of("amount", "2000000", "currencyCode", "TZS"),
                "completedAt", Instant.now().toString()));
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status())
            .as("a LOAN_DISBURSEMENT payout must never settle a claim")
            .isEqualTo(ClaimStatus.SETTLEMENT_REQUESTED);
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    /**
     * I2: on this plan's own designed retry path (rail declines -> the claim returns to APPROVED ->
     * staff retry with a NEW idempotency key), a retry carrying a DIFFERENT approvedAmount used to
     * reach the event and the real disbursement while {@code claim.approved_amount} silently kept the
     * ORIGINAL value -- the reviewer measured a claim recording 2,000,000 against 9,999,999 actually
     * disbursed. It is now rejected with 409 rather than silently re-applied, because changing an
     * approved settlement amount must be explicit in the audit trail.
     *
     * <p>Asserts both directions, so this cannot pass by simply blocking all retries: the differing
     * amount is refused AND never reaches the rail, and the SAME amount with a new key still settles
     * normally.
     */
    @Test
    void aSettlementRetryCannotSilentlyChangeTheApprovedAmountButMayRepeatIt() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_FLOAT\"}")));

        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-E2E-RETRY-AMOUNT");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "e2e-retry-reg-01", "assessor-retry-01");

        // The rail declines, so the claim returns to APPROVED -- the real state a staff retry starts
        // from, not a contrived one.
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
            "MPESA-0712000024", "e2e-settle-retry-a-" + claimId, "manager-retry-01");
        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.APPROVED);

        // A retry with a DIFFERENT amount: 409, and nothing reaches the rail.
        assertThrows(tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException.class,
            () -> claimsApi.decideSettlement(claimId, true, new BigDecimal("9999999"), "TZS", null,
                "MPESA-0712000024", "e2e-settle-retry-b-" + claimId, "manager-retry-01"));
        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).approvedAmount())
            .as("the recorded approved amount must never diverge from what is disbursed")
            .isEqualByComparingTo("2000000");
        assertThat(disbursementRepository.findByIdempotencyKeyAndTenantId("e2e-settle-retry-b-" + claimId, tenantId))
            .as("a rejected re-decision must not reach payment at all")
            .isEmpty();
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));

        // A retry with the SAME amount (scale deliberately different -- 2000000.00 is the same
        // amount, not a conflict) and a new key still works, all the way to SETTLED.
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CLAIM-E2E-RETRY-OK\"}")));
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000.00"), "TZS", null,
            "MPESA-0712000024", "e2e-settle-retry-c-" + claimId, "manager-retry-01");
        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.SURRENDERED);
    }
}
