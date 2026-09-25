package tz.co.nlolo.lifeplatform.policyloan;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyAccount;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyAccountRepository;
import tz.co.nlolo.lifeplatform.policyloan.api.*;
import tz.co.nlolo.lifeplatform.policyloan.domain.LoanTransaction;
import tz.co.nlolo.lifeplatform.policyloan.infrastructure.LoanTransactionRepository;
import tz.co.nlolo.lifeplatform.product.api.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * Task 6, Step 6 -- the real end-to-end proof that policyloan's disbursement loop is actually
 * closed: {@code policyLoanApi.originateLoan} (real API) -> {@code policyloan.LoanDisbursementRequested}
 * (real event) -> {@code payment.PaymentRequestListener} (real listener) -> the mobile-money
 * gateway (in-process WireMock stand-in, same pattern as
 * {@code MobileMoneyGatewayAdapterTest}/{@code PaymentRequestListenerIntegrationTest}) ->
 * {@code payment.DisbursementCompleted}/{@code DisbursementFailed} (real event) ->
 * {@code policyloan.application.PaymentEventListener} (Task 6's new listener) ->
 * {@code PolicyLoanApi.markDisbursed}/{@code markDisbursementFailed}. No test in this class ever
 * calls {@code markDisbursed}/{@code markDisbursementFailed} directly.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} chains are synchronous, same-thread:
 * {@code originateLoan}'s own {@code @Transactional} commits when the method returns (before
 * control comes back to this test), firing payment's listener synchronously; that listener's own
 * REQUIRES_NEW transactions (record request, call the gateway, complete-or-fail) commit and fire
 * policyloan's listener synchronously in turn, before the gateway call even returns to
 * {@code originateLoan}'s caller. The whole chain is therefore complete by the time
 * {@code originateLoan} returns -- no {@code await()}/sleep anywhere in this class.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class LoanDisbursementEndToEndTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @DynamicPropertySource
    static void mobileMoneyProperties(DynamicPropertyRegistry registry) {
        registry.add("mobile-money.base-url", () -> wireMock.baseUrl());
    }

    @BeforeAll
    static void startGatewayAndApplyMigrations() throws Exception {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        // Base list mirrors PolicyLoanContractTest's own migration list, plus payment/V1 and
        // payment/V2 -- this is the one test class in the suite that needs the FULL chain's
        // schema (policyloan, policy, AND payment) live at once.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
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
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/policyloan/V5__loan_interest_accrual.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V6__disbursement_method.sql");
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private PolicyLoanApi policyLoanApi;
    @Autowired private PolicyAccountRepository policyAccountRepository;
    @Autowired private LoanTransactionRepository loanTransactionRepository;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
        wireMock.resetAll();
    }

    /** Mirrors PolicyLoanApiIntegrationTest.issuePolicyWithCashValue exactly. */
    private String issuePolicyWithCashValue(UUID tenantId, BigDecimal cashValue, String productCode) throws Exception {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("E2E Loan Applicant", LocalDate.of(1990, 1, 1),
            "+255713099" + Math.abs(productCode.hashCode() % 1000), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "E2E Loan Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            cashValue, "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY", null, List.of(), "E2E loan test issuance");
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(policyNumber);

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("UPDATE policy.policy_account SET cash_value_amount = ? WHERE policy_number = ?")) {
            statement.setBigDecimal(1, cashValue);
            statement.setString(2, policyNumber);
            assertThat(statement.executeUpdate())
                .as("cash-value seed for %s must update exactly one policy_account row", policyNumber)
                .isEqualTo(1);
        }
        return policyNumber;
    }

    private BigDecimal encumbranceOf(String policyNumber) {
        return policyAccountRepository.findById(policyNumber)
            .map(PolicyAccount::getLoanEncumbranceAmount)
            .orElseThrow(() -> new AssertionError("Expected a policy_account row for " + policyNumber));
    }

    @Test
    void originateLoanRunsTheRealChainThroughToDisbursedWithTheGatewaysOwnReference() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-E2E-SUCCESS");
        TenantContext.set(tenantId);

        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-E2E-SUCCESS\"}")));

        // 1. Originate over the real API -- the loan legitimately rests at
        // DISBURSEMENT_REQUESTED in the object THIS CALL returns, because that view is built
        // from the in-memory entity at the end of originateLoan's own method body, before its
        // transaction (and therefore the AFTER_COMMIT chain) has committed.
        LoanView originated = policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent");
        assertThat(originated.status()).isEqualTo(LoanStatus.DISBURSEMENT_REQUESTED);

        // 2. By the time originateLoan has RETURNED, the whole chain (policyloan -> event ->
        // payment -> gateway -> event -> policyloan) has already run synchronously and
        // committed. No markDisbursed call anywhere in this test.
        LoanView reloaded = policyLoanApi.getLoan(originated.loanId());
        assertThat(reloaded.status()).isEqualTo(LoanStatus.DISBURSED);

        List<LoanTransaction> transactions = loanTransactionRepository.findByLoanIdOrderByOccurredAt(originated.loanId());
        LoanTransaction disbursement = transactions.stream()
            .filter(t -> "DISBURSEMENT".equals(t.getTransactionType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected a DISBURSEMENT loan_transaction row for " + originated.loanId()));
        // The load-bearing proof that the REAL gateway ran (not a stand-in reference): the
        // reference on the ledger row is the stub's own gatewayReference.
        assertThat(disbursement.getReference()).isEqualTo("MM-E2E-SUCCESS");

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void aRejectedDisbursementReleasesTheEncumbranceBackToItsNonZeroPreOriginationValue() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("2000000"), "LOAN-E2E-FAILURE");
        TenantContext.set(tenantId);

        // First loan: accepted, so this policy's encumbrance is genuinely non-zero BEFORE the
        // loan under test is even originated -- this is what makes the "encumbrance returns to
        // its pre-origination value" assertion below non-vacuous. An always-zero
        // decreaseEncumbrance (or one that resets rather than subtracts) would fail this test,
        // whereas it would trivially "pass" against a policy that started at zero.
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-E2E-FIRST-LOAN\"}")));
        LoanView firstLoan = policyLoanApi.originateLoan(policyNumber, new BigDecimal("300000"), "TZS", "MPESA-0700000001", "test-agent");
        assertThat(policyLoanApi.getLoan(firstLoan.loanId()).status()).isEqualTo(LoanStatus.DISBURSED);

        BigDecimal preOriginationEncumbrance = encumbranceOf(policyNumber);
        assertThat(preOriginationEncumbrance).as("pre-origination encumbrance must be non-zero for this test to be falsifiable")
            .isEqualByComparingTo("300000");

        // Second loan: the gateway now rejects it.
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_FLOAT\"}")));
        LoanView secondLoan = policyLoanApi.originateLoan(policyNumber, new BigDecimal("200000"), "TZS", "MPESA-0700000002", "test-agent");

        // 3. Reaches DISBURSEMENT_FAILED, with a REVERSAL transaction and the encumbrance
        // genuinely released.
        LoanView reloaded = policyLoanApi.getLoan(secondLoan.loanId());
        assertThat(reloaded.status()).isEqualTo(LoanStatus.DISBURSEMENT_FAILED);

        List<LoanTransaction> transactions = loanTransactionRepository.findByLoanIdOrderByOccurredAt(secondLoan.loanId());
        LoanTransaction reversal = transactions.stream()
            .filter(t -> "REVERSAL".equals(t.getTransactionType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected a REVERSAL loan_transaction row for " + secondLoan.loanId()));
        assertThat(reversal.getAmount()).isEqualByComparingTo("200000");

        // The load-bearing assertion: back to the SAME non-zero value recorded before this
        // second loan was originated -- not merely "some" value, and not zero.
        BigDecimal postFailureEncumbrance = encumbranceOf(policyNumber);
        assertThat(postFailureEncumbrance).isEqualByComparingTo(preOriginationEncumbrance);

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }
}
