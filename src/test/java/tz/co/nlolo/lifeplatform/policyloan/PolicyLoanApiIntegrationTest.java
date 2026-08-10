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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

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
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            // Every policyloan event this test triggers (LoanOriginated, LoanDisbursementRequested,
            // etc.) is picked up application-wide by audit.DomainEventAuditListener, which
            // persists an audit_log row regardless of which module published the event -- without
            // this migration every such test fails with "relation audit.audit_log does not exist",
            // same as policy.PolicyApiIntegrationTest's own migration list already needs it for.
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private PolicyLoanApi policyLoanApi;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private String issuePolicyWithCashValue(UUID tenantId, BigDecimal cashValue, String productCode) throws Exception {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Loan Test Applicant", LocalDate.of(1990, 1, 1), "+255713098" + Math.abs(productCode.hashCode() % 1000), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Loan Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            cashValue, "TZS", null, "MONTHLY", List.of(), "Loan test issuance");
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("UPDATE policy.policy_account SET cash_value_amount = ? WHERE policy_number = ?")) {
            statement.setBigDecimal(1, cashValue);
            statement.setString(2, policyNumber);
            statement.executeUpdate();
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
        policyLoanApi.markDisbursed(originated.loanId()); // test seam standing in for payment.DisbursementCompleted

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
        policyLoanApi.markDisbursed(originated.loanId());

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
