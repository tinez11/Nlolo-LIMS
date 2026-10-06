package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionStatement;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionStatementRepository;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
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
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
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

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * Task 7, Step 5 -- the real end-to-end proof that the two Task 7 enrichments {@code
 * claims.ClaimSettled} (M8, already amount-bearing) and the newly-enriched {@code
 * distribution.CommissionPaid} actually drive GL posting all the way through the real chains, not
 * hand-published payloads.
 *
 * <p><b>Claim leg:</b> {@code ClaimsApi.decideSettlement} (real API) -> {@code
 * claims.ClaimSettlementRequested} -> {@code payment.PaymentRequestListener} -> the mobile-money
 * rail (in-process WireMock, {@code ClaimSettlementEndToEndTest}'s exact pattern) -> {@code
 * payment.DisbursementCompleted} -> {@code claims.application.PaymentEventListener} -> {@code
 * claims.ClaimSettled} -> {@code finaccounting.application.ClaimsEventListener} posts DR {@code
 * 5000}/CR {@code 1000}.
 *
 * <p><b>Commission leg:</b> mirrors {@code CommissionPayoutEndToEndTest}'s harness exactly (real
 * policy issuance under a commission plan produces a real FIRST_YEAR accrual and its OPEN
 * statement; the statement is closed the same way that class does, standing in for Task 9's
 * pg_cron closer) -- then {@code DistributionApi.requestStatementPayout} (real API) -> {@code
 * distribution.CommissionPayoutRequested} -> {@code payment.PaymentRequestListener} -> the same
 * WireMock rail -> {@code payment.DisbursementCompleted} -> {@code
 * distribution.application.PaymentEventListener} -> the Task 7-enriched {@code
 * distribution.CommissionPaid} -> {@code finaccounting.application.DistributionEventListener}
 * posts DR {@code 5100}/CR {@code 1000}.
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS). {@code
 * policyloan/V1}/{@code V2} are required even though this test never touches a loan -- {@code
 * gl_posting}'s two hand-written partitions only inherit RLS/append-only privileges through {@code
 * policyloan/V2}'s cross-module event trigger ({@code trg_partition_controls}), the same
 * structural reason {@code FinaccountingApiIntegrationTest} and {@code PremiumPostingEndToEndTest}
 * both need it.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} chains are synchronous, same
 * thread: each producer's own {@code @Transactional} commits when its call returns, firing the
 * next listener in the chain before control comes back to this test. No await/sleep anywhere here.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(ClaimAndCommissionPostingEndToEndTest.EventRecorderConfiguration.class)
class ClaimAndCommissionPostingEndToEndTest {

    private static final String APP_ROLE_PASSWORD = "claim_commission_posting_e2e_password";
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
        // Union of ClaimSettlementEndToEndTest's and CommissionPayoutEndToEndTest's own migration
        // lists, plus policyloan/V1-V2 for gl_posting's partition controls (see class javadoc) --
        // this is the one test class in the suite that needs claims', distribution's AND
        // finaccounting's schemas live at once.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V4__seed_distribution_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
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
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/policy/V37__sale_classification.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql",
            "db-migrations/distribution/V5__agent_channel_and_home_branch.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql",
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

    /** Records every published domain event so "exactly one posting, and none on redelivery" is
     * assertable directly -- same pattern as PremiumPostingEndToEndTest/CommissionPayoutEndToEndTest. */
    @TestConfiguration
    static class EventRecorderConfiguration {
        @Bean
        EventRecorder claimAndCommissionPostingTestEventRecorder() { return new EventRecorder(); }
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
    @Autowired private DistributionApi distributionApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private CommissionStatementRepository commissionStatementRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private GlPostingRepository glPostingRepository;
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
        PartyView applicant = partyApi.registerIndividual("Claim/Commission Posting E2E Applicant " + productCode,
            LocalDate.of(1985, 3, 1), "+25571900" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)),
            null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Claim/Commission Posting E2E Product " + productCode,
            ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    /** Mirrors ClaimSettlementEndToEndTest.issuePolicyWithNullUnderwritingCase. */
    private String issuePolicyWithNullUnderwritingCase(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("2000000"), CURRENCY, new BigDecimal("40000.00"), CURRENCY, "MONTHLY", null, List.of(),
            "Claim/Commission posting E2E test");
        String issuedPolicyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    /** Registers, assesses, and returns a fresh DEATH claim id, ready to approve -- mirrors
     * ClaimSettlementEndToEndTest.registerAndAssessDeathClaim. */
    private UUID registerAndAssessDeathClaim(UUID tenantId, Fixture fixture, String policyNumber, String regKey, String assessor) {
        TenantContext.set(tenantId);
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, null, fixture.applicantId(),
            ClaimType.DEATH, LocalDate.now().minusDays(1),
            new DeathClaimDetails("Natural causes", "Dar es Salaam", LocalDate.now().minusDays(1), "Dr. Test"));
        UUID claimId = claimsApi.registerClaim(request, regKey, "claims-staff").claimId();
        claimsApi.submitAssessment(claimId, "Consistent with cause of death", new BigDecimal("2000000"), CURRENCY, false, assessor, null);
        return claimId;
    }

    /** Mirrors CommissionPayoutEndToEndTest.closedStatementFor exactly: issues a real policy under
     * a real FIRST_YEAR commission plan/agent, producing a real accrual and its OPEN statement,
     * then closes it directly (standing in for Task 9's pg_cron closer, which cannot run inside
     * this test's synchronous chain).
     *
     * @return the CLOSED statement's id, with a total of 10% of the premium
     */
    private UUID closedStatementFor(UUID tenantId, String tag, BigDecimal premium) {
        TenantContext.set(tenantId);
        ProductSummaryView product = productApi.createProduct("CLAIM-COMM-" + tag, "Claim/Commission Posting Product " + tag,
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

        PartyView agentParty = partyApi.registerIndividual("Claim/Commission Posting Agent " + tag, LocalDate.of(1985, 1, 1),
            "+25573" + String.format("%07d", Math.abs(tag.hashCode() % 10000000)), null, "test-agent");
        partyApi.submitKycEvidence(agentParty.partyId(), KycStatus.VERIFIED, "doc-ref-" + tag, "kyc-officer");
        UUID agentId = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            agentParty.partyId(), "LIC-CLAIM-COMM-" + tag, LocalDate.now().plusYears(1), null), "staff-1").agentId();

        PartyView policyholder = partyApi.registerIndividual("Claim/Commission Posting Policyholder " + tag,
            LocalDate.of(1980, 6, 1), "+25574" + String.format("%07d", Math.abs(tag.hashCode() % 10000000)), null, "test-agent");
        String policyNumber = policyApi.issuePolicy(null, new PolicyApi.IssueRequest(policyholder.partyId(),
            product.productId(), productVersionId, new BigDecimal("2000000"), CURRENCY, premium, CURRENCY,
            "MONTHLY", agentId, List.of(), "Claim/Commission posting E2E test"), "test-staff").policyNumber();
        // Commission is only posted once there is cover to earn it on.
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

    private List<GlPosting> legsFor(UUID tenantId, JournalEntry entry) {
        TenantContext.set(tenantId);
        return glPostingRepository.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(tenantId, entry.getJournalEntryId());
    }

    private static GlPosting legFor(List<GlPosting> legs, String accountCode) {
        return legs.stream().filter(l -> accountCode.equals(l.getAccountCode())).findFirst()
            .orElseThrow(() -> new AssertionError("No leg found for account " + accountCode));
    }

    private JournalEntry singleEntryFor(UUID tenantId, String sourceEvent, String sourceRef) {
        TenantContext.set(tenantId);
        List<JournalEntry> entries = journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).stream()
            .filter(e -> sourceEvent.equals(e.getSourceEvent()) && sourceRef.equals(e.getSourceRef()))
            .toList();
        assertThat(entries).as("expected exactly one %s journal entry for sourceRef %s", sourceEvent, sourceRef).hasSize(1);
        return entries.get(0);
    }

    @Test
    void settlingAClaimAndPayingOutACommissionEachPostOneBalancedEntryAndRedeliveryAddsNothing() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CLAIM-COMM-E2E\"}")));

        UUID tenantId = UUID.randomUUID();

        // ---- Claim leg: real settlement, all the way through payment's rail. ----
        Fixture fixture = buildFixture(tenantId, "CLAIM-COMM-E2E-CLAIM");
        String policyNumber = issuePolicyWithNullUnderwritingCase(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "claim-comm-e2e-reg-01", "assessor-claim-comm-01");
        String settleKey = "claim-comm-e2e-settle-" + claimId;
        eventRecorder.clear();

        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), CURRENCY, null,
            "MPESA-0715000001", settleKey, "manager-claim-comm-01");

        TenantContext.set(tenantId);
        assertThat(claimsApi.getClaim(claimId).status()).isEqualTo(ClaimStatus.SETTLED);

        // ---- Assertion 1: the claim-settled journal entry, DR 5000 / CR 1000. ----
        JournalEntry claimEntry = singleEntryFor(tenantId, "claims.ClaimSettled", claimId.toString());
        List<GlPosting> claimLegs = legsFor(tenantId, claimEntry);
        assertThat(claimLegs).hasSize(2);
        assertThat(claimLegs).extracting(GlPosting::getAccountCode)
            .containsExactlyInAnyOrder("5110", "1140");
        assertThat(legFor(claimLegs, "5110").getDirection()).isEqualTo(PostingDirection.DR);
        assertThat(legFor(claimLegs, "1140").getDirection()).isEqualTo(PostingDirection.CR);
        assertThat(legFor(claimLegs, "5110").getAmount()).isEqualByComparingTo("2000000");

        // ---- Commission leg: a real closed statement, paid out through the same rail. ----
        UUID statementId = closedStatementFor(tenantId, "CLAIM-COMM-E2E-COMM", new BigDecimal("100000.00"));
        String payoutKey = "claim-comm-e2e-payout-" + statementId;
        eventRecorder.clear();

        TenantContext.set(tenantId);
        distributionApi.requestStatementPayout(statementId, "MPESA-0715000002", payoutKey, "finance-officer");

        TenantContext.set(tenantId);
        assertThat(commissionStatementRepository.findByStatementIdAndTenantId(statementId, tenantId)
            .orElseThrow().getStatus()).isEqualTo(StatementStatus.PAID);

        // ---- Assertion 2: the commission-paid journal entry, DR 5100 / CR 1000. ----
        JournalEntry commissionEntry = singleEntryFor(tenantId, "distribution.CommissionPaid", statementId.toString());
        List<GlPosting> commissionLegs = legsFor(tenantId, commissionEntry);
        assertThat(commissionLegs).hasSize(2);
        assertThat(commissionLegs).extracting(GlPosting::getAccountCode)
            .containsExactlyInAnyOrder("2123", "1140");
        assertThat(legFor(commissionLegs, "2123").getDirection()).isEqualTo(PostingDirection.DR);
        assertThat(legFor(commissionLegs, "1140").getDirection()).isEqualTo(PostingDirection.CR);
        // 10% of 100000.00 -- the accrual really drove the payout, and therefore the posted amount.
        assertThat(legFor(commissionLegs, "2123").getAmount()).isEqualByComparingTo("10000.00");

        // ---- Assertion 3: a redelivery of each source event adds nothing -- finaccounting's own
        // ux_journal_entry_once-backed idempotency, not the producer's. Republished directly onto
        // the bus (not through a second decideSettlement/requestStatementPayout call, which cannot
        // legally re-run either transition), field-for-field from each producer's real payload. ----
        eventRecorder.clear();
        Map<String, Object> claimSettledPayload = Map.of(
            "claimId", claimId,
            "policyNumber", policyNumber,
            "settledAmount", Map.of("amount", "2000000", "currencyCode", CURRENCY),
            "settledAt", Instant.now().toString());
        var claimEnvelope = DomainEventEnvelope.of("claims.ClaimSettled", tenantId, claimSettledPayload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(claimEnvelope));

        Map<String, Object> commissionPaidPayload = Map.of(
            "statementId", statementId,
            "paidAt", Instant.now().toString(),
            "amount", Map.of("amount", "10000.00", "currencyCode", CURRENCY));
        var commissionEnvelope = DomainEventEnvelope.of("distribution.CommissionPaid", tenantId, commissionPaidPayload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(commissionEnvelope));

        TenantContext.set(tenantId);
        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).stream()
                .filter(e -> "claims.ClaimSettled".equals(e.getSourceEvent())).toList())
            .as("a redelivered ClaimSettled must not double-post").hasSize(1);
        TenantContext.set(tenantId);
        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).stream()
                .filter(e -> "distribution.CommissionPaid".equals(e.getSourceEvent())).toList())
            .as("a redelivered CommissionPaid must not double-post").hasSize(1);
        assertThat(legsFor(tenantId, claimEntry)).as("no second posting pair for the claim entry").hasSize(2);
        assertThat(legsFor(tenantId, commissionEntry)).as("no second posting pair for the commission entry").hasSize(2);
        assertThat(eventRecorder.ofType("finaccounting.GlPostingRecorded"))
            .as("neither redelivery may publish a second GlPostingRecorded")
            .isEmpty();

        wireMock.verify(exactly(2), postRequestedFor(urlPathEqualTo("/disburse")));
    }
}
