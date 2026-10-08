package tz.co.nlolo.lifeplatform.policyloan;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policyloan.api.*;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

@Testcontainers
@SpringBootTest(classes = Application.class)
class PolicyLoanApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
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
            "db-migrations/product/V30__funeral_group_rate_period.sql",
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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
            "db-migrations/policy/V38__group_funeral_scheme.sql",
            "db-migrations/policy/V40__commencement_never_null.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/policyloan/V5__loan_interest_accrual.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/policyloan/V8__interest_month_published.sql",
            // Every policyloan event this test triggers (LoanOriginated, LoanDisbursementRequested,
            // etc.) is picked up application-wide by audit.DomainEventAuditListener, which
            // persists an audit_log row regardless of which module published the event -- without
            // this migration every such test fails with "relation audit.audit_log does not exist",
            // same as policy.PolicyApiIntegrationTest's own migration list already needs it for.
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private PolicyLoanApi policyLoanApi;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private String issuePolicyWithCashValue(UUID tenantId, BigDecimal cashValue, String productCode) throws Exception {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Loan Test Applicant", LocalDate.of(1990, 1, 1), "+255713098" + Math.abs(productCode.hashCode() % 1000), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Loan Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            cashValue, "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY", null, List.of(), "Loan test issuance");
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(policyNumber);

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("UPDATE policy.policy_account SET cash_value_amount = ? WHERE policy_number = ?")) {
            statement.setBigDecimal(1, cashValue);
            statement.setString(2, policyNumber);
            // Asserted, not discarded (M3 final review, I4 -- the same guard Task 8 added to
            // PolicyLoanContractTest and ModuleArchitectureB1EndToEndRaceTest after proving
            // with a two-run control that the vacuous path is real). A seed that matched zero
            // rows would leave cash value at the 0.00 PolicyApiImpl.issuePolicy creates the
            // account with, and originateLoanRejectsAnAmountExceedingAvailableLoanValue below
            // -- which deliberately seeds a SMALL 100,000 and asserts
            // InsufficientLoanValueException on a 500,000 request -- would still throw, having
            // proven nothing about PolicyAccount.availableLoanValue's arithmetic. That test
            // would also pass against `return BigDecimal.ZERO;`. This guard is what makes it
            // non-vacuous; the other tests here seed large values and would fail loudly on
            // their own.
            assertThat(statement.executeUpdate())
                .as("cash-value seed for %s must update exactly one policy_account row", policyNumber)
                .isEqualTo(1);
        }
        return policyNumber;
    }

    @Test
    void originateLoanReservesConfirmsAndRestsAtDisbursementRequested() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-ORIGINATE-01");
        TenantContext.set(tenantId);

        LoanView loan = policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent");
        assertEquals(LoanStatus.DISBURSEMENT_REQUESTED, loan.status());
        assertEquals(0, new BigDecimal("500000").compareTo(loan.principalAmount()));
        assertEquals(0, new BigDecimal("12.0").compareTo(loan.currentInterestRate())); // TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE, PLACEHOLDER
    }

    @Test
    void originateLoanRejectsAnAmountExceedingAvailableLoanValue() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("100000"), "LOAN-ORIGINATE-02");
        TenantContext.set(tenantId);

        assertThrows(tz.co.nlolo.lifeplatform.policy.api.InsufficientLoanValueException.class, () ->
            policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent"));
    }

    @Test
    void originateLoanRejectsAPolicyThatIsNotInForce() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-ORIGINATE-03");
        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff");

        assertThrows(LoanNotEligibleException.class, () ->
            policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent"));
    }

    @Test
    void repaymentReducesOutstandingBalanceAndSettlesAtZero() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-REPAY-01");
        TenantContext.set(tenantId);
        LoanView originated = policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent");
        policyLoanApi.markDisbursed(originated.loanId(), "MM-TEST-REF", Instant.now()); // stands in for consuming payment.DisbursementCompleted

        LoanView afterFirstRepayment = policyLoanApi.recordRepayment(originated.loanId(), new BigDecimal("200000"), "TZS", "PAY-REF-01", "test-agent");
        assertEquals(LoanStatus.REPAYING, afterFirstRepayment.status());
        assertEquals(0, new BigDecimal("300000").compareTo(afterFirstRepayment.outstandingBalance()));

        LoanView afterFullRepayment = policyLoanApi.recordRepayment(originated.loanId(), new BigDecimal("300000"), "TZS", "PAY-REF-02", "test-agent");
        assertEquals(LoanStatus.SETTLED, afterFullRepayment.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(afterFullRepayment.outstandingBalance()));
    }

    @Test
    void recordRepaymentRejectsALoanStillAtDisbursementRequested() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-REPAY-02");
        TenantContext.set(tenantId);
        LoanView originated = policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent");

        // Never called markDisbursed -- loan legitimately rests at DISBURSEMENT_REQUESTED
        // (Global Constraints: payment, its real trigger, is M5).
        assertThrows(LoanNotEligibleException.class, () ->
            policyLoanApi.recordRepayment(originated.loanId(), new BigDecimal("100000"), "TZS", "PAY-REF-03", "test-agent"));
    }

    @Test
    void triggerForcedLapsePublishesAndTransitionsFromRepaying() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-FORCELAPSE-01");
        TenantContext.set(tenantId);
        LoanView originated = policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent");
        policyLoanApi.markDisbursed(originated.loanId(), "MM-TEST-REF", Instant.now());

        LoanView forced = policyLoanApi.triggerForcedLapse(originated.loanId(), "cash value exhausted");
        assertEquals(LoanStatus.FORCED_LAPSE_TRIGGERED, forced.status());
    }

    @Test
    void listLoansForPolicyReturnsEveryLoanAgainstThatPolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("2000000"), "LOAN-LIST-01");
        TenantContext.set(tenantId);
        policyLoanApi.originateLoan(policyNumber, new BigDecimal("100000"), "TZS", "MPESA-0712345678", "test-agent");

        List<LoanView> loans = policyLoanApi.listLoansForPolicy(policyNumber);
        assertThat(loans).hasSize(1);
        assertThat(loans.get(0).policyNumber()).isEqualTo(policyNumber);
    }

    /**
     * M3 final review, I1 -- the REACHABILITY half of the proof. The mapping half (that
     * {@code OptimisticLockingFailureException} becomes a 409 {@code CONCURRENT_MODIFICATION}
     * over real HTTP) is {@code OptimisticLockingConflictContractTest}; without this test that
     * one would pass against an exception no production code path can actually raise.
     *
     * <p>{@code PolicyLoan} carries {@code @Version} and {@code recordRepayment} takes no
     * pessimistic lock, so two concurrent repayments against the same DISBURSED loan both pass
     * the eligibility gate, both dirty-check DISBURSED -> REPAYING, and both issue
     * {@code UPDATE ... WHERE version = N}. Reproduced DETERMINISTICALLY rather than with a
     * two-thread barrier (which could pass without either request ever losing a race): the
     * loan is loaded into this transaction's persistence context at version N, a separate
     * already-committed connection bumps the row to N+1, and only then does
     * {@code recordRepayment} -- joining this same transaction, and therefore seeing the
     * first-level-cached entity still at version N -- dirty it and flush. The service code
     * under test is the real, unmodified {@code PolicyLoanApiImpl.recordRepayment}.
     */
    @Test
    void concurrentRepaymentLosesTheVersionRaceAndRaisesAnOptimisticLockingFailure() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"), "LOAN-OPTLOCK-01");
        TenantContext.set(tenantId);
        LoanView originated = policyLoanApi.originateLoan(policyNumber, new BigDecimal("500000"), "TZS", "MPESA-0712345678", "test-agent");
        policyLoanApi.markDisbursed(originated.loanId(), "MM-TEST-REF", Instant.now());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status -> {
            policyLoanApi.getLoan(originated.loanId());          // version N into the persistence context
            bumpLoanVersionOnACommittedSideConnection(originated.loanId());  // the concurrent winner
            return policyLoanApi.recordRepayment(originated.loanId(), new BigDecimal("100000"), "TZS",
                "MPESA-REPAY-OPTLOCK", "test-agent");            // flushes UPDATE ... WHERE version = N
        })).isInstanceOf(OptimisticLockingFailureException.class);
    }

    /** Stands in for the winning concurrent request: commits a version bump on its own
     * connection, outside the caller's transaction. Asserted, not discarded -- a zero-row update
     * here would make the test above pass for the wrong reason (no conflict, no exception... and
     * then it would fail, so this is belt-and-braces rather than a vacuity guard). */
    private void bumpLoanVersionOnACommittedSideConnection(UUID loanId) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE policyloan.policy_loan SET version = version + 1 WHERE loan_id = ?")) {
            statement.setObject(1, loanId);
            assertThat(statement.executeUpdate())
                .as("the simulated concurrent writer must update exactly one policy_loan row")
                .isEqualTo(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // Review fix (Task 6 fix round 1, I1): deleted
    // confirmingAReservationThatTheOpportunisticSweepAlreadyExpiredIsRejected from here. It
    // called policyApi.reserveLoanValue/confirmReservation directly and never touched
    // policyLoanApi.originateLoan, so it was testing policy, not policyloan -- and it was a
    // near-duplicate of policy.ModuleArchitectureB1ConcurrencyTest
    // .confirmReservationExplicitlyRejectsAnAlreadyExpiredReservation, which proves the identical
    // PolicyApi-level behavior more deterministically (it forces the sweep directly via
    // loanValueReservationRepository.expireStaleReservations instead of racing a second
    // reserveLoanValue call) and additionally asserts the rejected reservation's persisted status
    // and that the encumbrance amount was never applied -- strictly more than the deleted test
    // checked.
    //
    // Why policyloan has no test of its own for this scenario: under the single-physical-
    // transaction design documented in PolicyLoanApiImpl.originateLoan (Global Constraints;
    // see the comment above its reserveLoanValue call), the reservation originateLoan creates
    // is never visible to any other transaction before this same transaction's own
    // confirmReservation call runs -- so it can never be flipped to EXPIRED by a concurrent
    // call's opportunistic TTL sweep first. The "TTL sweep expires the reservation mid-flight"
    // race is therefore UNREACHABLE through originateLoan by construction, and a same-transaction
    // test that tried to reproduce it here would be contrived and could not fail for the reason
    // it claims to test. This becomes reachable from originateLoan -- and must then be tested
    // here -- only once M5 splits policy's and policyloan's transactions apart (the deferred,
    // genuine two-phase protocol).
}
