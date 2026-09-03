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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The forced-lapse chain, end to end and through the REAL accrual sweep:
 * interest accrues in SQL, the loan is flagged for review, per-tenant Java runs the shortfall
 * test against the policy's cash value, and the POLICY is terminated.
 *
 * <p><b>What this closes.</b> {@code docs/01-domain-map.md:224} defines Forced Lapse as
 * "automatic policy termination when loan balance plus interest exceeds cash value". None of that
 * worked. Nothing accrued interest, so the balance never grew; nothing evaluated the shortfall,
 * so {@code triggerForcedLapse} was called only from
 * {@code PolicyLoanApiIntegrationTest} and {@code FORCED_LAPSE_TRIGGERED} was unreachable in
 * production; and {@code policyloan.LoanForcedLapseTriggered} had NO CONSUMER anywhere, so even
 * when the loan transitioned the policy stayed in force. A policy loan could outgrow its
 * collateral indefinitely with no consequence.
 *
 * <p>Every test below asserts the POLICY's status, not just the loan's. Asserting the loan alone
 * is what would have let the missing-consumer half of this bug pass review: the loan transitioned
 * correctly the whole time.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ForcedLapseEndToEndTest {

    private static final Path SWEEP_FILE = Path.of("db-migrations/_post-migration/configure-loan-interest-accrual.sql");

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
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/policyloan/V5__loan_interest_accrual.sql",
            // audit.DomainEventAuditListener persists a row for every domain event this test
            // triggers, regardless of publishing module -- without this, every such test fails
            // with "relation audit.audit_log does not exist".
            "db-migrations/audit/V1__create_audit_schema.sql");

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            // The accrual function itself, so this test drives the REAL sweep rather than a
            // hand-written stand-in for it. pg_cron is not installed in this container, so only
            // the CREATE FUNCTION half is applied -- the cron.schedule line is covered by
            // LoanInterestAccrualSweepPsqlTest.theScheduledCommandStringActuallyExecutes.
            String fullFile = Files.readString(SWEEP_FILE);
            try (Statement statement = connection.createStatement()) {
                statement.execute(fullFile.substring(0, fullFile.indexOf("-- Daily at 03:00")));
            }
            // V1 hand-writes only the 2026-08 and 2026-09 loan_transaction partitions, deferring
            // the rest to pg_partman, which is not installed here. Without this the class starts
            // failing the moment the calendar leaves 2026-09 ("no partition of relation ... found
            // for row") -- date-fragile rather than genuinely broken.
            for (YearMonth month : new YearMonth[] {YearMonth.now().minusMonths(1), YearMonth.now(), YearMonth.now().plusMonths(1)}) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(String.format(
                        "CREATE TABLE IF NOT EXISTS policyloan.loan_transaction_%s PARTITION OF policyloan.loan_transaction "
                        + "FOR VALUES FROM ('%s-01') TO ('%s-01')", month.toString().replace('-', '_'), month, month.plusMonths(1)));
                }
            }
        }
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private PolicyLoanApi policyLoanApi;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    /** Same idiom as PolicyLoanApiIntegrationTest.issuePolicyWithCashValue, including its
     * asserted seed: a cash-value seed matching zero rows would leave the account at the 0.00
     * issuePolicy creates it with, and several assertions below would then pass vacuously. */
    private String issuePolicyWithCashValue(UUID tenantId, BigDecimal cashValue, String productCode) throws Exception {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Forced Lapse Applicant", LocalDate.of(1990, 1, 1),
            "+255713097" + Math.abs(productCode.hashCode() % 1000), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Forced Lapse Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(),
            snapshot.productVersionId(), cashValue, "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY", null,
            List.of(), "Forced lapse test issuance");
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();

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

    /**
     * Borrows the policy's entire available loan value and disburses it, then backdates the
     * DISBURSEMENT ledger entry so the sweep has a day to charge for.
     *
     * <p>Borrowing the full amount is what makes the shortfall reachable at all:
     * {@code PolicyAccount.availableLoanValue} caps origination at cash value, so a loan can
     * never START above its collateral -- only accrued interest can push it there. That is
     * precisely the mechanism that was dead code before the sweep existed.
     */
    private LoanView disbursedLoanAtFullCashValue(String policyNumber, BigDecimal cashValue, int disbursedDaysAgo) throws Exception {
        LoanView originated = policyLoanApi.originateLoan(policyNumber, cashValue, "TZS", "MPESA-0712345678", "test-agent");
        policyLoanApi.markDisbursed(originated.loanId(), "MM-TEST-REF", Instant.now());
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE policyloan.loan_transaction SET occurred_at = occurred_at - make_interval(days => ?) "
                 + "WHERE loan_id = ? AND transaction_type = 'DISBURSEMENT'")) {
            statement.setInt(1, disbursedDaysAgo);
            statement.setObject(2, originated.loanId());
            assertThat(statement.executeUpdate())
                .as("the disbursement entry must exist to be backdated, or the sweep would find nothing to accrue from")
                .isEqualTo(1);
        }
        return originated;
    }

    private static void runAccrualSweep() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement sweep = connection.createStatement()) {
            sweep.execute("SELECT policyloan.accrue_loan_interest()");
        }
    }

    private static String policyStatusOf(String policyNumber) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement select = connection.prepareStatement("SELECT status FROM policy.policy WHERE policy_number = ?")) {
            select.setString(1, policyNumber);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    /**
     * The whole chain, and the test that fails against every state this platform was in before
     * this work: SQL accrues -> the loan is flagged -> per-tenant Java tests the shortfall
     * against cash value -> the loan force-lapses AND the policy is terminated.
     */
    @Test
    void accruedInterestPushesTheLoanPastCashValueAndTerminatesThePolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        BigDecimal cashValue = new BigDecimal("1000000.00");
        String policyNumber = issuePolicyWithCashValue(tenantId, cashValue, "LOAN-FL-CHAIN");
        TenantContext.set(tenantId);
        LoanView loan = disbursedLoanAtFullCashValue(policyNumber, cashValue, 1);

        // NEGATIVE CONTROLS. The balance exactly equals cash value, so there is no shortfall yet
        // and the policy is in force -- meaning nothing below can pass unless the sweep genuinely
        // grows the balance and the evaluation genuinely acts on it.
        assertThat(policyLoanApi.getLoan(loan.loanId()).outstandingBalance()).isEqualByComparingTo(cashValue);
        assertThat(policyLoanApi.evaluateForcedLapse(loan.loanId()).status())
            .as("a balance exactly equal to cash value is still fully collateralized")
            .isEqualTo(LoanStatus.DISBURSED);
        assertThat(policyStatusOf(policyNumber)).isEqualTo("ACTIVE");

        runAccrualSweep();

        // One day at the seeded 12% placeholder rate: 1,000,000 x 12% x 1/365 = 328.77.
        assertThat(policyLoanApi.getLoan(loan.loanId()).outstandingBalance())
            .as("the sweep must actually have grown the balance")
            .isEqualByComparingTo("1000328.77");

        LoanView evaluated = policyLoanApi.evaluateForcedLapse(loan.loanId());

        assertEquals(LoanStatus.FORCED_LAPSE_TRIGGERED, evaluated.status());
        assertThat(policyStatusOf(policyNumber))
            .as("THE half that was missing: LoanForcedLapseTriggered had no consumer, so the "
                + "policy stayed in force no matter how far the loan outgrew its collateral")
            .isEqualTo("LAPSED");
    }

    /** A healthy loan must be left completely alone -- and un-flagged, so it stops re-queueing. */
    @Test
    void aLoanWellWithinCashValueIsUntouchedAndDequeued() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000.00"), "LOAN-FL-HEALTHY");
        TenantContext.set(tenantId);
        LoanView originated = policyLoanApi.originateLoan(policyNumber, new BigDecimal("100000.00"), "TZS", "MPESA-0712345678", "test-agent");
        policyLoanApi.markDisbursed(originated.loanId(), "MM-TEST-REF", Instant.now());
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "UPDATE policyloan.loan_transaction SET occurred_at = occurred_at - make_interval(days => 10) "
                 + "WHERE loan_id = ? AND transaction_type = 'DISBURSEMENT'")) {
            statement.setObject(1, originated.loanId());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        runAccrualSweep();
        assertThat(policyLoanApi.listLoansPendingForcedLapseReview())
            .as("the sweep accrued, so this loan is queued for the shortfall test")
            .extracting(LoanView::loanId).contains(originated.loanId());

        LoanView evaluated = policyLoanApi.evaluateForcedLapse(originated.loanId());

        assertEquals(LoanStatus.DISBURSED, evaluated.status());
        assertThat(policyStatusOf(policyNumber)).isEqualTo("ACTIVE");
        assertThat(policyLoanApi.listLoansPendingForcedLapseReview())
            .as("clearing the flag on the healthy path too, or this loan re-queues on every pass forever")
            .extracting(LoanView::loanId).doesNotContain(originated.loanId());
    }

    /**
     * Draining a queue means asking about loans that have since gone terminal, so a re-evaluation
     * must be a no-op rather than an exception. {@code markForcedLapseTriggered} throws on any
     * status but DISBURSED/REPAYING, which is correct for the manual path and wrong for this one.
     */
    @Test
    void reEvaluatingAnAlreadyForcedLapsedLoanIsANoOpNotAnError() throws Exception {
        UUID tenantId = UUID.randomUUID();
        BigDecimal cashValue = new BigDecimal("1000000.00");
        String policyNumber = issuePolicyWithCashValue(tenantId, cashValue, "LOAN-FL-IDEMPOTENT");
        TenantContext.set(tenantId);
        LoanView loan = disbursedLoanAtFullCashValue(policyNumber, cashValue, 1);
        runAccrualSweep();

        assertEquals(LoanStatus.FORCED_LAPSE_TRIGGERED, policyLoanApi.evaluateForcedLapse(loan.loanId()).status());

        assertEquals(LoanStatus.FORCED_LAPSE_TRIGGERED, policyLoanApi.evaluateForcedLapse(loan.loanId()).status());
        assertThat(policyLoanApi.listLoansPendingForcedLapseReview())
            .extracting(LoanView::loanId).doesNotContain(loan.loanId());
    }

    /**
     * If the policy cannot be lapsed -- already LAPSED, SURRENDERED, MATURED, or the REINSTATED
     * state {@code Policy.lapse()} refuses despite {@code isPolicyInForce()} treating it as in
     * force -- the loan-side transition and the event must STILL stand. Swallowing the outcome
     * entirely is what made the original missing-consumer bug invisible, so the event records
     * whether cover was actually terminated.
     */
    @Test
    void theLoanTransitionStandsEvenWhenThePolicyCannotBeLapsed() throws Exception {
        UUID tenantId = UUID.randomUUID();
        BigDecimal cashValue = new BigDecimal("1000000.00");
        String policyNumber = issuePolicyWithCashValue(tenantId, cashValue, "LOAN-FL-ALREADY");
        TenantContext.set(tenantId);
        LoanView loan = disbursedLoanAtFullCashValue(policyNumber, cashValue, 1);
        runAccrualSweep();

        // Lapsed by some other route first (arrears, say), so PolicyApi.lapsePolicy will refuse.
        policyApi.lapsePolicy(policyNumber, "test-staff");
        assertThat(policyStatusOf(policyNumber)).isEqualTo("LAPSED");

        LoanView evaluated = policyLoanApi.evaluateForcedLapse(loan.loanId());

        assertEquals(LoanStatus.FORCED_LAPSE_TRIGGERED, evaluated.status());
        assertThat(policyStatusOf(policyNumber)).isEqualTo("LAPSED");
    }

    /** The review queue is tenant-scoped, like every other read on this API. */
    @Test
    void theReviewQueueNeverLeaksAnotherTenantsLoans() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        BigDecimal cashValue = new BigDecimal("1000000.00");

        String policyA = issuePolicyWithCashValue(tenantA, cashValue, "LOAN-FL-TENANT-A");
        TenantContext.set(tenantA);
        LoanView loanA = disbursedLoanAtFullCashValue(policyA, cashValue, 1);

        String policyB = issuePolicyWithCashValue(tenantB, cashValue, "LOAN-FL-TENANT-B");
        TenantContext.set(tenantB);
        LoanView loanB = disbursedLoanAtFullCashValue(policyB, cashValue, 1);

        runAccrualSweep();

        TenantContext.set(tenantA);
        assertThat(policyLoanApi.listLoansPendingForcedLapseReview()).extracting(LoanView::loanId)
            .contains(loanA.loanId()).doesNotContain(loanB.loanId());

        TenantContext.set(tenantB);
        assertThat(policyLoanApi.listLoansPendingForcedLapseReview()).extracting(LoanView::loanId)
            .contains(loanB.loanId()).doesNotContain(loanA.loanId());
    }
}
