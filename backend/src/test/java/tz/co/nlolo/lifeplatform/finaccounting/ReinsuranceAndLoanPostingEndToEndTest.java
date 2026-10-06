package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanStatus;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanView;
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.LoanTransactionRepository;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.CessionRepository;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ClaimRecoveryRepository;
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
import java.sql.PreparedStatement;
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
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * Task 7, Step 5 -- the real end-to-end proof that the two Task 7 enrichments {@code
 * reinsurance.RecoveryConfirmed} and {@code policyloan.LoanDisbursed} actually drive GL posting
 * all the way through the real chains, and that {@code 1400 Policy Loan Receivable} nets to zero
 * across a disbursement and the TWO PARTIAL repayments that clear it -- the loan-side mirror of the
 * premium receivable invariant {@code PremiumPostingEndToEndTest} already proved for {@code 1200}.
 * The two-partial-repayment shape arrived with M9's final-review fix wave; see the note below.
 *
 * <p><b>Cession + recovery leg:</b> {@code PolicyApi.issuePolicy} under a real 50% QUOTA_SHARE
 * treaty (real API) -> {@code policy.PolicyIssued} -> {@code
 * reinsurance.application.PolicyEventListener} -> a real cession -> {@code
 * reinsurance.CessionRecorded} -> {@code finaccounting.application.ReinsuranceEventListener} posts
 * DR {@code 5200}/CR {@code 2300}. Then {@code ClaimsApi.decideSettlement} (real API) -> the real
 * payment request/confirm loop (in-process WireMock, {@code ClaimSettlementEndToEndTest}'s exact
 * pattern) -> {@code claims.ClaimSettled} -> {@code reinsurance.application.ClaimEventListener} ->
 * a real recovery row -> {@code ReinsuranceApi.confirmRecovery} (real API) -> the Task
 * 7-enriched {@code reinsurance.RecoveryConfirmed} -> {@code ReinsuranceEventListener} posts DR
 * {@code 1300}/CR {@code 5000}.
 *
 * <p><b>Loan leg:</b> mirrors {@code LoanDisbursementEndToEndTest}'s harness (a real policy with a
 * seeded cash value): {@code PolicyLoanApi.originateLoan} (real API) -> {@code
 * policyloan.LoanDisbursementRequested} -> {@code payment.PaymentRequestListener} -> the same
 * WireMock rail -> {@code payment.DisbursementCompleted} -> {@code
 * policyloan.application.PaymentEventListener} -> the Task 7-enriched {@code
 * policyloan.LoanDisbursed} -> {@code finaccounting.application.PolicyLoanEventListener} posts DR
 * {@code 1400}/CR {@code 1000}. Then <b>TWO</b> {@code PolicyLoanApi.recordRepayment} calls (real
 * API, M3's immediately-confirmed simplification -- no gateway hop), each for HALF the principal ->
 * two {@code policyloan.LoanRepaid} events -> {@code PolicyLoanEventListener} posts DR {@code
 * 1000}/CR {@code 1400} <b>twice</b>.
 *
 * <p><b>The two-partial-repayment shape is the point, not incidental detail.</b> This test
 * originally drove one full-principal repayment in a single call, which is the only repayment shape
 * under which M9's Critical bug could not manifest: {@code PolicyLoanEventListener} keyed the
 * posting's idempotency on {@code loanId}, identical across every repayment of one loan, so the
 * second partial repayment was silently discarded as a false redelivery and {@code 1400 Policy Loan
 * Receivable} never cleared -- while this test's net-to-zero assertion passed vacuously. The fix
 * (enriching {@code LoanRepaid} with the repayment's own {@code loanTransactionId}) is falsifiable
 * only against two repayments, so that is what this test now drives.
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS). {@code
 * policyloan/V1}-{@code V4} are needed for the loan scenario itself, and {@code policyloan/V2} is
 * listed deliberately (not merely relied upon to ride along) for the same cross-module {@code
 * trg_partition_controls} reason {@code FinaccountingApiIntegrationTest} documents: {@code
 * gl_posting}'s two hand-written partitions only inherit RLS/append-only privileges through it.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} chains are synchronous, same
 * thread: each producer's own {@code @Transactional} commits when its call returns, firing the
 * next listener in the chain before control comes back to this test. No await/sleep anywhere here.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(ReinsuranceAndLoanPostingEndToEndTest.EventRecorderConfiguration.class)
class ReinsuranceAndLoanPostingEndToEndTest {

    private static final String APP_ROLE_PASSWORD = "reinsurance_loan_posting_e2e_password";
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
        // Union of RecoveryEndToEndTest's and LoanDisbursementEndToEndTest's own migration lists,
        // plus finaccounting's own -- this is the one test class in the suite that needs
        // reinsurance's, claims', policyloan's AND finaccounting's schemas live at once.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
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
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            "db-migrations/reinsurance/V4__projection_product_category.sql",
            "db-migrations/reinsurance/V5__bordereau.sql",
            "db-migrations/reinsurance/V6__scheme_may_open_empty.sql",
            "db-migrations/reinsurance/V7__statement.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/policyloan/V5__loan_interest_accrual.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/policyloan/V8__interest_month_published.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql",
            "db-migrations/payment/V8__benefit_payout_purposes.sql",
            "db-migrations/payment/V9__account_purposes.sql",
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
            "db-migrations/finaccounting/V14__manual_journals.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    /** Records every published domain event -- same AFTER_COMMIT pattern as CessionEndToEndTest/
     * RecoveryEndToEndTest/PremiumPostingEndToEndTest. */
    @TestConfiguration
    static class EventRecorderConfiguration {
        @Bean
        EventRecorder reinsuranceAndLoanPostingTestEventRecorder() { return new EventRecorder(); }
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
    @Autowired private tz.co.nlolo.lifeplatform.reinsurance.application.BordereauJob bordereauJob;
    @Autowired private PolicyLoanApi policyLoanApi;
    /** Read directly for the same reason CessionRepository/ClaimRecoveryRepository are below: this
     * test has to assert against the REAL rows a producer wrote, not just against its API view. */
    @Autowired private LoanTransactionRepository loanTransactionRepository;
    @Autowired private CessionRepository cessionRepository;
    @Autowired private ClaimRecoveryRepository claimRecoveryRepository;
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

    /** Mirrors RecoveryEndToEndTest/ClaimSettlementEndToEndTest.buildFixture. */
    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Reinsurance/Loan Posting E2E Applicant " + productCode,
            LocalDate.of(1985, 3, 1), "+25572000" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)),
            null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Reinsurance/Loan Posting E2E Product " + productCode,
            ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private void createQuotaShareTreaty(UUID tenantId, BigDecimal cessionPercent) {
        TenantContext.set(tenantId);
        reinsuranceApi.createTreaty(new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), CURRENCY, cessionPercent,
            new BigDecimal("20.00"), null, LocalDate.now().minusMonths(1), null), "finance-officer");
    }

    /** Sum assured fixed at 2,000,000 -- the DEATH claim below settles for the full sum assured,
     * so the cession and recovery amounts fall out of clean arithmetic against the treaty's 50%. */
    private String issuePolicy(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), CURRENCY, new BigDecimal("100000.00"), CURRENCY,
            "MONTHLY", null, List.of(), "Reinsurance/Loan posting E2E test");
        String issuedPolicyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    private UUID registerAndAssessDeathClaim(UUID tenantId, Fixture fixture, String policyNumber, String regKey, String assessor) {
        TenantContext.set(tenantId);
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, null, fixture.applicantId(),
            ClaimType.DEATH, LocalDate.now().minusDays(1),
            new DeathClaimDetails("Natural causes", "Dar es Salaam", LocalDate.now().minusDays(1), "Dr. Test"));
        UUID claimId = claimsApi.registerClaim(request, regKey, "claims-staff").claimId();
        claimsApi.submitAssessment(claimId, "Consistent with cause of death", new BigDecimal("2000000"), CURRENCY, false, assessor, null);
        return claimId;
    }

    /** Mirrors LoanDisbursementEndToEndTest.issuePolicyWithCashValue exactly, over a superuser
     * connection for the direct cash-value seed (the app datasource is app_role, NOSUPERUSER
     * NOBYPASSRLS, but this update runs against the container's own default credentials, the same
     * way FinaccountingApiIntegrationTest's cross-tenant check does). */
    private String issuePolicyWithCashValue(UUID tenantId, BigDecimal cashValue, String productCode) throws Exception {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Reinsurance/Loan Posting E2E Loan Applicant", LocalDate.of(1990, 1, 1),
            "+255713098" + Math.abs(productCode.hashCode() % 1000), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Reinsurance/Loan Posting E2E Loan Product",
            ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            cashValue, CURRENCY, new BigDecimal("50000.00"), CURRENCY, "MONTHLY", null, List.of(),
            "Reinsurance/Loan posting E2E loan test issuance");
        String policyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(policyNumber);

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE policy.policy_account SET cash_value_amount = ? WHERE policy_number = ?")) {
            statement.setBigDecimal(1, cashValue);
            statement.setString(2, policyNumber);
            assertThat(statement.executeUpdate())
                .as("cash-value seed for %s must update exactly one policy_account row", policyNumber)
                .isEqualTo(1);
        }
        return policyNumber;
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

    /** Signed net movement of {@code accountCode} across a journal entry's own legs: DR adds, CR
     * subtracts -- the same convention PremiumPostingEndToEndTest's own net-to-zero assertion uses. */
    private static BigDecimal netFor(List<GlPosting> legs, String accountCode) {
        BigDecimal net = BigDecimal.ZERO;
        for (GlPosting leg : legs) {
            if (accountCode.equals(leg.getAccountCode())) {
                net = leg.getDirection() == PostingDirection.DR ? net.add(leg.getAmount()) : net.subtract(leg.getAmount());
            }
        }
        return net;
    }

    @Autowired private tz.co.nlolo.lifeplatform.reinsurance.application.ReinsuranceStatements statements;

    /** Every leg the tenant has posted -- to see an account's whole balance, not one journal's movement. */
    private List<GlPosting> everyLeg(UUID tenantId) {
        TenantContext.set(tenantId);
        List<GlPosting> all = new java.util.ArrayList<>();
        for (JournalEntry e : journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged())) {
            all.addAll(legsFor(tenantId, e));
        }
        return all;
    }

    /**
     * IFRS 17 I3d: a quarter's statement clears exactly what its bordereaux (K-01, K-02) and recoveries (B-05) posted.
     * Afterwards premium payable 1430, commission receivable 1431 and recoveries 1420 are zero, the current account 1434
     * carries what the reinsurer owes, and profit commission sits on 1433 -- one SYSTEM journal (R-01, R-03).
     */
    @Test
    void aQuartersStatementClearsTheBordereauxAndRecoveriesIntoTheCurrentAccount() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-REINS-STMT-E2E\"}")));
        UUID tenantId = UUID.randomUUID();
        createQuotaShareTreaty(tenantId, new BigDecimal("50.00"));
        Fixture fixture = buildFixture(tenantId, "REINS-STMT-E2E");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "reins-stmt-e2e-reg", "assessor-stmt");
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), CURRENCY, null,
            "MPESA-0716000002", "reins-stmt-e2e-settle-" + claimId, "manager-stmt");

        var quarter = tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator.Quarter.of(
            LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam")));
        bordereauJob.drain(quarter.endExclusive());     // every month of this quarter written

        TenantContext.set(tenantId);
        UUID treatyId = reinsuranceApi.listTreaties(null).get(0).treatyId();
        var draft = statements.prepare(treatyId, quarter.toString(), "finance-one", quarter.endExclusive());
        assertThat(draft.recoveries()).as("the death claim's 50%").isEqualByComparingTo("1000000.00");
        reinsuranceApi.attachStatementDocument(draft.statementId(), "doc-statement", "finance-one");
        reinsuranceApi.updateStatement(draft.statementId(), BigDecimal.ZERO, new BigDecimal("1000.00"), "Africa Re agreed",
            "finance-one");
        reinsuranceApi.submitStatement(draft.statementId(), "finance-one");
        reinsuranceApi.approveStatement(draft.statementId(), "finance-approver");

        JournalEntry statement = singleEntryFor(tenantId, "reinsurance.StatementApproved", draft.statementId().toString());
        assertThat(statement.getSourceType()).isEqualTo(tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource.SYSTEM);
        assertThat(legsFor(tenantId, statement)).allSatisfy(l -> assertThat(l.getDimensions().reference())
            .isEqualTo(draft.statementId().toString()));

        List<GlPosting> all = everyLeg(tenantId);
        assertThat(netFor(all, "1430")).as("premium payable cleared").isZero();
        assertThat(netFor(all, "1431")).as("commission receivable cleared").isZero();
        assertThat(netFor(all, "1420")).as("recoveries cleared").isZero();
        assertThat(netFor(all, "1434")).as("the reinsurer owes the recovery and commission, less the premium")
            .isEqualByComparingTo(draft.owedToUs());
        assertThat(netFor(all, "1433")).isEqualByComparingTo("1000.00");
    }

    @Test
    void cessionRecoveryAndLoanDisburseThenTwoPartialRepaymentsEachPostTheirOwnBalancedEntryAndTheLoanReceivableNetsToZero() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-REINS-LOAN-E2E\"}")));

        UUID tenantId = UUID.randomUUID();

        // ---- Cession leg: real policy issuance under a real 50% QUOTA_SHARE treaty. ----
        createQuotaShareTreaty(tenantId, new BigDecimal("50.00"));
        Fixture fixture = buildFixture(tenantId, "REINS-LOAN-E2E-CESSION");
        eventRecorder.clear();
        String policyNumber = issuePolicy(tenantId, fixture);

        TenantContext.set(tenantId);
        assertThat(cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber))
            .as("the policy is ceded").hasSize(1);

        // ---- Assertion 1 (IFRS 17 I3c): a cession posts nothing -- it used to post the ceded SUM ASSURED as premium.
        TenantContext.set(tenantId);
        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).stream()
            .filter(e -> e.getSourceEvent().startsWith("reinsurance.")).toList()).isEmpty();

        // ---- Assertion 2: the month's bordereau posts K-01 and K-02 -- 50% of the 100,000 monthly premium ceded
        // (Dr 1436 / Cr 1430 50,000), less the treaty's 20% commission not contingent on claims (Dr 1431 / Cr 1436
        // 10,000) -- dated the month's last day. ----
        bordereauJob.drain(LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam")).withDayOfMonth(1).plusMonths(1));
        TenantContext.set(tenantId);
        JournalEntry bordereauEntry = journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged())
            .stream().filter(e -> "reinsurance.BordereauPosted".equals(e.getSourceEvent())).findFirst()
            .orElseThrow(() -> new AssertionError("Expected the month's bordereau journal"));
        assertThat(bordereauEntry.getPeriod()).isEqualTo(java.time.YearMonth.now(java.time.ZoneId.of("Africa/Dar_es_Salaam")).toString());
        List<GlPosting> bordereauLegs = legsFor(tenantId, bordereauEntry);
        assertThat(bordereauLegs).extracting(GlPosting::getAccountCode)
            .containsExactlyInAnyOrder("1436", "1430", "1431", "1436");
        assertThat(netFor(bordereauLegs, "1430")).isEqualByComparingTo("-50000.00");
        assertThat(netFor(bordereauLegs, "1431")).isEqualByComparingTo("10000.00");
        assertThat(netFor(bordereauLegs, "1436")).as("the ceded premium net of the commission").isEqualByComparingTo("40000.00");

        // ---- Recovery leg: a real DEATH claim approved for the full sum assured -- the recovery posts at approval
        // (B-05), with no Confirm. ----
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "reins-loan-e2e-reg-01", "assessor-reins-loan-01");
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), CURRENCY, null,
            "MPESA-0716000001", "reins-loan-e2e-settle-" + claimId, "manager-reins-loan-01");

        TenantContext.set(tenantId);
        UUID recoveryId = claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId).stream().findFirst()
            .orElseThrow(() -> new AssertionError("Expected a recovery row for claim " + claimId))
            .getRecoveryId();

        // ---- Assertion 3: Dr 1420 / Cr 6120 -- the reinsurance result, never netted against the claims expense --
        // for 50% of the approved amount (2,000,000 -> 1,000,000 recoverable). ----
        JournalEntry recoveryEntry = singleEntryFor(tenantId, "reinsurance.RecoveryCalculated", recoveryId.toString());
        assertThat(recoveryEntry.getPolicyNumber()).isEqualTo(policyNumber);
        List<GlPosting> recoveryLegs = legsFor(tenantId, recoveryEntry);
        assertThat(recoveryLegs).hasSize(2);
        assertThat(recoveryLegs).extracting(GlPosting::getAccountCode)
            .containsExactlyInAnyOrder("1420", "6120");
        assertThat(legFor(recoveryLegs, "1420").getDirection()).isEqualTo(PostingDirection.DR);
        assertThat(legFor(recoveryLegs, "6120").getDirection()).isEqualTo(PostingDirection.CR);
        assertThat(legFor(recoveryLegs, "1420").getAmount()).isEqualByComparingTo("1000000.00");

        // ---- Loan leg: real disbursement through the same rail, then a real, matching repayment. ----
        String loanPolicyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "REINS-LOAN-E2E-LOAN");
        BigDecimal principal = new BigDecimal("500000");
        eventRecorder.clear();

        TenantContext.set(tenantId);
        LoanView originated = policyLoanApi.originateLoan(loanPolicyNumber, principal, CURRENCY, "MPESA-0716000002", "test-agent");

        TenantContext.set(tenantId);
        assertThat(policyLoanApi.getLoan(originated.loanId()).status()).isEqualTo(LoanStatus.DISBURSED);

        // ---- Assertion 3: the loan-disbursed journal entry, DR 1400 / CR 1000. ----
        JournalEntry disbursedEntry = singleEntryFor(tenantId, "policyloan.LoanDisbursed", originated.loanId().toString());
        List<GlPosting> disbursedLegs = legsFor(tenantId, disbursedEntry);
        assertThat(disbursedLegs).hasSize(2);
        assertThat(disbursedLegs).extracting(GlPosting::getAccountCode)
            .containsExactlyInAnyOrder("2125", "1140");
        assertThat(legFor(disbursedLegs, "2125").getDirection()).isEqualTo(PostingDirection.DR);
        assertThat(legFor(disbursedLegs, "1140").getDirection()).isEqualTo(PostingDirection.CR);
        assertThat(legFor(disbursedLegs, "2125").getAmount()).isEqualByComparingTo(principal);

        // ---- TWO PARTIAL repayments, each for HALF the principal -- M3's immediately-confirmed
        // simplification, no gateway hop (PolicyLoanApiImpl.recordRepayment's own javadoc), and the
        // real repayment shape PolicyLoanApiIntegrationTest
        // .repaymentReducesOutstandingBalanceAndSettlesAtZero already exercised on the policyloan
        // side (200,000 then 300,000 against a 500,000 loan).
        //
        // THIS IS THE CASE THE ORIGINAL VERSION OF THIS TEST COULD NOT SEE. It drove one
        // full-principal repayment in a single call, so exactly one LoanRepaid was ever published
        // and its net-to-zero proof held vacuously. Meanwhile finaccounting keyed that posting's
        // idempotency on loanId, which is IDENTICAL across every repayment of one loan -- so the
        // second partial repayment was misread as a redelivery of the first and dropped on
        // postEntry's fast-path: silently, with no exception, no
        // lifeplatform_finaccounting_event_processing_failed_total increment and no alert, leaving
        // 1400 Policy Loan Receivable permanently half-open on a fully-repaid loan while the trial
        // balance still balanced. Fixed by keying on the repayment's own loanTransactionId; the
        // "two separate journal entries" assertion below is what makes that fix falsifiable.
        BigDecimal half = new BigDecimal("250000");
        assertThat(half.add(half)).as("the two partial repayments must sum to exactly the principal")
            .isEqualByComparingTo(principal);

        TenantContext.set(tenantId);
        LoanView afterFirstHalf = policyLoanApi.recordRepayment(originated.loanId(), half, CURRENCY,
            "repay-ref-reins-loan-e2e-1", "finance-officer-reins-loan-01");
        assertThat(afterFirstHalf.status())
            .as("a partial repayment leaves the loan REPAYING, not SETTLED").isEqualTo(LoanStatus.REPAYING);
        assertThat(afterFirstHalf.outstandingBalance()).isEqualByComparingTo(half);

        // Exactly ONE entry so far: proves the second assertion below is measuring a real second
        // posting rather than something that was already there.
        assertThat(repaidEntriesFor(tenantId))
            .as("the first partial repayment must post exactly one journal entry").hasSize(1);

        TenantContext.set(tenantId);
        LoanView afterSecondHalf = policyLoanApi.recordRepayment(originated.loanId(), half, CURRENCY,
            "repay-ref-reins-loan-e2e-2", "finance-officer-reins-loan-01");
        assertThat(afterSecondHalf.status())
            .as("the repayment that clears the balance settles the loan").isEqualTo(LoanStatus.SETTLED);
        assertThat(afterSecondHalf.outstandingBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // ---- Assertion 4: TWO separate policyloan.LoanRepaid journal entries exist -- one per
        // partial repayment, not one, not zero. This is the assertion that would have caught the
        // Critical. ----
        List<JournalEntry> repaidEntries = repaidEntriesFor(tenantId);
        assertThat(repaidEntries)
            .as("each partial repayment must post its OWN journal entry -- one entry here means the "
                + "second repayment was silently swallowed as a false redelivery")
            .hasSize(2);

        // ...and each is keyed on its own repayment's loan_transaction id, which is what makes the
        // two distinguishable in the first place. Asserted against the real REPAYMENT rows rather
        // than against "two distinct strings", so a fix that keyed on something merely unique (a
        // random UUID per publish, say) would fail here -- such a key would break real redelivery
        // idempotency, which is the other half of what this sourceRef has to do.
        TenantContext.set(tenantId);
        List<String> repaymentTransactionIds = loanTransactionRepository
            .findByLoanIdOrderByOccurredAt(originated.loanId()).stream()
            .filter(t -> "REPAYMENT".equals(t.getTransactionType()))
            .map(t -> t.getLoanTransactionId().toString())
            .toList();
        assertThat(repaymentTransactionIds).as("two REPAYMENT loan_transaction rows must exist").hasSize(2);
        assertThat(repaidEntries).extracting(JournalEntry::getSourceRef)
            .as("each LoanRepaid entry's sourceRef must be its own repayment's loanTransactionId")
            .containsExactlyInAnyOrderElementsOf(repaymentTransactionIds);

        // Both entries are balanced DR 1000 Cash / CR 1400 Policy Loan Receivable for their half.
        for (JournalEntry repaidEntry : repaidEntries) {
            List<GlPosting> repaidLegs = legsFor(tenantId, repaidEntry);
            assertThat(repaidLegs).hasSize(2);
            assertThat(repaidLegs).extracting(GlPosting::getAccountCode)
                .containsExactlyInAnyOrder("1140", "2125");
            assertThat(legFor(repaidLegs, "1140").getDirection()).isEqualTo(PostingDirection.DR);
            assertThat(legFor(repaidLegs, "2125").getDirection()).isEqualTo(PostingDirection.CR);
            assertThat(legFor(repaidLegs, "2125").getAmount()).isEqualByComparingTo(half);
        }

        // ---- Assertion 5: 1400 Policy Loan Receivable nets to EXACTLY zero across the disbursement
        // and BOTH partial repayments -- the loan-side mirror of PremiumPostingEndToEndTest's 1200
        // invariant, now proven under the case that actually exercises the fix. Before it, this
        // summed to +250,000: half the loan stayed on the balance sheet forever. ----
        BigDecimal netLoanReceivable = netFor(disbursedLegs, "2125");
        for (JournalEntry repaidEntry : repaidEntries) {
            netLoanReceivable = netLoanReceivable.add(netFor(legsFor(tenantId, repaidEntry), "2125"));
        }
        assertThat(netLoanReceivable)
            .as("1400 Policy Loan Receivable must net to zero across disburse plus BOTH partial repayments")
            .isEqualByComparingTo(BigDecimal.ZERO);

        wireMock.verify(exactly(2), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    /** Every {@code policyloan.LoanRepaid} journal entry in this tenant. The tenant id is freshly
     * random per test and exactly one loan is repaid in it, so this is "the entries for that loan"
     * -- and it deliberately does NOT filter on a sourceRef, since the whole point of the fix under
     * test is that a LoanRepaid entry is no longer keyed by the loan id. */
    private List<JournalEntry> repaidEntriesFor(UUID tenantId) {
        TenantContext.set(tenantId);
        return journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).stream()
            .filter(e -> "policyloan.LoanRepaid".equals(e.getSourceEvent()))
            .toList();
    }
}
