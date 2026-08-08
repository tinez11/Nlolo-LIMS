package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module-Architecture-B1 (docs/02-module-architecture.md §3.4/§3.5) -- the loan-origination
 * race condition, NOT the Aggregate-Design-doc's unrelated "B1" (TZ_REINSTATEMENT_WINDOW_MONTHS
 * -- see this plan's header disambiguation). Proves the specific race the reserve/confirm/
 * release protocol closes: two concurrent reserveLoanValue calls against the SAME policy, each
 * individually affordable but jointly exceeding available cash value, must never both succeed.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ModuleArchitectureB1ConcurrencyTest {

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
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    /** issuePolicy starts cash value at zero (Task 2 -- real cash value only accrues via
     * premium payment, billing, M4, out of scope). This test needs a real available balance to
     * race against, so it seeds policy_account.cash_value_amount directly via JDBC right after
     * issuance -- a test-only shortcut, not a new production code path. */
    private String issuePolicyWithCashValue(UUID tenantId, BigDecimal cashValue) throws SQLException {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Concurrency Test Applicant", LocalDate.of(1990, 1, 1), "+255713099999", null, "test-agent");
        ProductSummaryView product = productApi.createProduct("B1-RACE-" + UUID.randomUUID().toString().substring(0, 6), "Race Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")), null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            cashValue, "TZS", null, "MONTHLY", List.of(), "Concurrency test issuance");
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
    void concurrentReservationsAgainstTheSamePolicyNeverJointlyOverdraw() throws Exception {
        UUID tenantId = UUID.randomUUID();
        BigDecimal cashValue = new BigDecimal("1000000");
        String policyNumber = issuePolicyWithCashValue(tenantId, cashValue);
        BigDecimal eachRequest = new BigDecimal("700000"); // two of these (1,400,000) exceed cashValue; one alone (700,000) fits

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Callable<UUID>> tasks = List.of(
            () -> attemptReservation(tenantId, policyNumber, eachRequest, barrier),
            () -> attemptReservation(tenantId, policyNumber, eachRequest, barrier));
        List<Future<UUID>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        int successCount = 0;
        int failureCount = 0;
        for (Future<UUID> future : futures) {
            try {
                if (future.get() != null) successCount++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof InsufficientLoanValueException) {
                    failureCount++;
                } else {
                    throw e;
                }
            }
        }
        assertThat(successCount).isEqualTo(1);
        assertThat(failureCount).isEqualTo(1);

        // Confirms the DB row itself, not just the Java-level return values: exactly one
        // RESERVED reservation exists, summing to eachRequest -- the race genuinely never let
        // both writes land.
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT COUNT(*), COALESCE(SUM(amount), 0) FROM policy.loan_value_reservation WHERE policy_number = ? AND status = 'RESERVED'")) {
            statement.setString(1, policyNumber);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
                assertThat(resultSet.getBigDecimal(2)).isEqualByComparingTo(eachRequest);
            }
        }
    }

    /** TenantContext is a ThreadLocal -- MUST be set inside the task running on the
     * ExecutorService's own worker thread, not only on the test's main thread (Global
     * Constraints) -- otherwise every reserveLoanValue call inside the Callable would throw
     * TenantContext's fail-loud IllegalStateException instead of racing at all. */
    private UUID attemptReservation(UUID tenantId, String policyNumber, BigDecimal amount, CyclicBarrier barrier) throws Exception {
        TenantContext.set(tenantId);
        try {
            barrier.await(5, TimeUnit.SECONDS); // maximizes the actual race window
            return policyApi.reserveLoanValue(policyNumber, amount, "TZS", Duration.ofMinutes(15));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void expiredReservationSelfHealsOnNextReserveLoanValueCall() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"));
        TenantContext.set(tenantId);

        // A reservation with a TTL already in the past -- simulates the crash case
        // (originateLoan reserved, then crashed before confirm/release ran).
        UUID staleReservationId = policyApi.reserveLoanValue(policyNumber, new BigDecimal("900000"), "TZS", Duration.ofMillis(1));
        Thread.sleep(50);

        // Without Step 1's sweep, this would still see 900,000 "RESERVED" and reject a fresh
        // 900,000 request as exceeding the 1,000,000 available. The opportunistic sweep at the
        // top of reserveLoanValue expires the stale row first, making room -- proving the
        // "crash case self-heals on next access" requirement without a cross-tenant
        // @Scheduled job (Global Constraints).
        UUID newReservationId = policyApi.reserveLoanValue(policyNumber, new BigDecimal("900000"), "TZS", Duration.ofMinutes(15));
        assertThat(newReservationId).isNotEqualTo(staleReservationId);
    }
}
