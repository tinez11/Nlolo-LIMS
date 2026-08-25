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
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql");
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private void createQuotaShareTreaty(UUID tenantId, BigDecimal cessionPercent) {
        TenantContext.set(tenantId);
        reinsuranceApi.createTreaty(new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), CURRENCY, cessionPercent,
            LocalDate.now().minusMonths(1), null), "finance-officer");
    }

    /** Sum assured fixed at 2,000,000 -- the DEATH claim below settles for the full sum assured,
     * so the cession and recovery amounts fall out of clean arithmetic against the treaty's 50%. */
    private String issuePolicy(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), CURRENCY, new BigDecimal("100000.00"), CURRENCY,
            "MONTHLY", null, List.of(), "Reinsurance/Loan posting E2E test");
        return policyApi.issuePolicy(null, request, "test-staff").policyNumber();
    }

    private UUID registerAndAssessDeathClaim(UUID tenantId, Fixture fixture, String policyNumber, String regKey, String assessor) {
        TenantContext.set(tenantId);
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber, fixture.applicantId(),
            ClaimType.DEATH, LocalDate.now().minusDays(1),
            new DeathClaimDetails("Natural causes", "Dar es Salaam", LocalDate.now().minusDays(1), "Dr. Test"));
        UUID claimId = claimsApi.registerClaim(request, regKey, "claims-staff").claimId();
        claimsApi.submitAssessment(claimId, "Consistent with cause of death", new BigDecimal("2000000"), CURRENCY, false, assessor);
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            cashValue, CURRENCY, new BigDecimal("50000.00"), CURRENCY, "MONTHLY", null, List.of(),
            "Reinsurance/Loan posting E2E loan test issuance");
        String policyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();

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
        UUID cessionId = cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber)
            .stream().findFirst()
            .orElseThrow(() -> new AssertionError("Expected a cession row for " + policyNumber))
            .getCessionId();

        // ---- Assertion 1: the cession journal entry, DR 5200 / CR 2300, for 50% of the sum
        // assured (2,000,000 -> 1,000,000 ceded). ----
        JournalEntry cessionEntry = singleEntryFor(tenantId, "reinsurance.CessionRecorded", cessionId.toString());
        List<GlPosting> cessionLegs = legsFor(tenantId, cessionEntry);
        assertThat(cessionLegs).hasSize(2);
        assertThat(cessionLegs).extracting(GlPosting::getAccountCode)
            .containsExactlyInAnyOrder(PostingRule.REINSURANCE_CEDED_PREMIUM, PostingRule.REINSURANCE_PAYABLE);
        assertThat(legFor(cessionLegs, PostingRule.REINSURANCE_CEDED_PREMIUM).getDirection()).isEqualTo(PostingDirection.DR);
        assertThat(legFor(cessionLegs, PostingRule.REINSURANCE_PAYABLE).getDirection()).isEqualTo(PostingDirection.CR);
        assertThat(legFor(cessionLegs, PostingRule.REINSURANCE_CEDED_PREMIUM).getAmount()).isEqualByComparingTo("1000000.00");

        // ---- Recovery leg: a real DEATH claim settled for the full sum assured, through the real
        // payment rail, then a real confirmRecovery call. ----
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "reins-loan-e2e-reg-01", "assessor-reins-loan-01");
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), CURRENCY, null,
            "MPESA-0716000001", "reins-loan-e2e-settle-" + claimId, "manager-reins-loan-01");

        TenantContext.set(tenantId);
        UUID recoveryId = claimRecoveryRepository.findByTenantIdAndClaimId(tenantId, claimId).stream().findFirst()
            .orElseThrow(() -> new AssertionError("Expected a recovery row for claim " + claimId))
            .getRecoveryId();

        eventRecorder.clear();
        TenantContext.set(tenantId);
        reinsuranceApi.confirmRecovery(recoveryId, "finance-officer-reins-loan-01");

        // ---- Assertion 2: the recovery-confirmed journal entry, DR 1300 / CR 5000, for 50% of
        // the settled amount (2,000,000 -> 1,000,000 recoverable). ----
        JournalEntry recoveryEntry = singleEntryFor(tenantId, "reinsurance.RecoveryConfirmed", recoveryId.toString());
        List<GlPosting> recoveryLegs = legsFor(tenantId, recoveryEntry);
        assertThat(recoveryLegs).hasSize(2);
        assertThat(recoveryLegs).extracting(GlPosting::getAccountCode)
            .containsExactlyInAnyOrder(PostingRule.REINSURANCE_RECOVERABLE, PostingRule.CLAIMS_EXPENSE);
        assertThat(legFor(recoveryLegs, PostingRule.REINSURANCE_RECOVERABLE).getDirection()).isEqualTo(PostingDirection.DR);
        assertThat(legFor(recoveryLegs, PostingRule.CLAIMS_EXPENSE).getDirection()).isEqualTo(PostingDirection.CR);
        assertThat(legFor(recoveryLegs, PostingRule.REINSURANCE_RECOVERABLE).getAmount()).isEqualByComparingTo("1000000.00");

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
            .containsExactlyInAnyOrder(PostingRule.POLICY_LOAN_RECEIVABLE, PostingRule.CASH);
        assertThat(legFor(disbursedLegs, PostingRule.POLICY_LOAN_RECEIVABLE).getDirection()).isEqualTo(PostingDirection.DR);
        assertThat(legFor(disbursedLegs, PostingRule.CASH).getDirection()).isEqualTo(PostingDirection.CR);
        assertThat(legFor(disbursedLegs, PostingRule.POLICY_LOAN_RECEIVABLE).getAmount()).isEqualByComparingTo(principal);

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
                .containsExactlyInAnyOrder(PostingRule.CASH, PostingRule.POLICY_LOAN_RECEIVABLE);
            assertThat(legFor(repaidLegs, PostingRule.CASH).getDirection()).isEqualTo(PostingDirection.DR);
            assertThat(legFor(repaidLegs, PostingRule.POLICY_LOAN_RECEIVABLE).getDirection()).isEqualTo(PostingDirection.CR);
            assertThat(legFor(repaidLegs, PostingRule.POLICY_LOAN_RECEIVABLE).getAmount()).isEqualByComparingTo(half);
        }

        // ---- Assertion 5: 1400 Policy Loan Receivable nets to EXACTLY zero across the disbursement
        // and BOTH partial repayments -- the loan-side mirror of PremiumPostingEndToEndTest's 1200
        // invariant, now proven under the case that actually exercises the fix. Before it, this
        // summed to +250,000: half the loan stayed on the balance sheet forever. ----
        BigDecimal netLoanReceivable = netFor(disbursedLegs, PostingRule.POLICY_LOAN_RECEIVABLE);
        for (JournalEntry repaidEntry : repaidEntries) {
            netLoanReceivable = netLoanReceivable.add(netFor(legsFor(tenantId, repaidEntry), PostingRule.POLICY_LOAN_RECEIVABLE));
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
