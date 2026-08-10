package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policy.infrastructure.LoanValueReservationRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql");
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private LoanValueReservationRepository loanValueReservationRepository;

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
            // Asserted for consistency with the other three fixtures on this branch (M3 final
            // review, I4). This class self-guards -- every test here seeds a large cash value
            // and asserts a SUCCESSFUL reservation, so a zero-row seed fails all four loudly
            // rather than silently -- but leaving one of four copies of this fixture different
            // is exactly how the defect recurred in PolicyLoanApiIntegrationTest.
            assertThat(statement.executeUpdate())
                .as("cash-value seed for %s must update exactly one policy_account row", policyNumber)
                .isEqualTo(1);
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

    /** Task 3 review Critical finding #1: confirmReservation must not silently resurrect a
     * reservation the TTL sweep has already retired. This is the deterministic half of the
     * proof -- the sweep is run to completion (its own committed transaction) BEFORE
     * confirmReservation is even attempted, so there is no timing ambiguity: an already-EXPIRED
     * reservation must always be explicitly rejected, never confirmed. */
    @Test
    void confirmReservationExplicitlyRejectsAnAlreadyExpiredReservation() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"));
        TenantContext.set(tenantId);
        UUID reservationId = policyApi.reserveLoanValue(policyNumber, new BigDecimal("400000"), "TZS", Duration.ofMillis(1));
        Thread.sleep(50);

        int sweptRows = loanValueReservationRepository.expireStaleReservations(policyNumber, tenantId, java.time.Instant.now());
        assertThat(sweptRows).isEqualTo(1); // sanity: the row really is EXPIRED, deterministically, before confirm is attempted

        assertThatThrownBy(() -> policyApi.confirmReservation(reservationId))
            .isInstanceOf(InvalidPolicyStateException.class)
            .hasMessageContaining("EXPIRED");

        assertThat(readReservationStatus(reservationId)).isEqualTo("EXPIRED");
        assertThat(readEncumbranceAmount(policyNumber)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Same fix, the idempotent side: releaseReservation on an already-EXPIRED reservation must
     * remain a no-op (not an error) per its documented contract -- proving the new
     * PESSIMISTIC_WRITE lock didn't turn this pre-existing idempotency into a spurious
     * InvalidPolicyStateException. */
    @Test
    void releaseReservationOnAnAlreadyExpiredReservationIsIdempotentNotAnError() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"));
        TenantContext.set(tenantId);
        UUID reservationId = policyApi.reserveLoanValue(policyNumber, new BigDecimal("400000"), "TZS", Duration.ofMillis(1));
        Thread.sleep(50);
        assertThat(loanValueReservationRepository.expireStaleReservations(policyNumber, tenantId, java.time.Instant.now())).isEqualTo(1);

        policyApi.releaseReservation(reservationId); // must not throw

        assertThat(readReservationStatus(reservationId)).isEqualTo("EXPIRED");
    }

    /** THE Critical finding's falsifiable proof, real-threads-and-barrier style (Task 3 review,
     * "Prove it with a falsifiable test"). A reservation is created with a TTL already in the
     * past -- simulating the review's "slow policyloan transaction + TTL expiry" scenario: the
     * hold is stale, but nothing has swept it yet, so its DB row still reads RESERVED. Two real
     * threads then race, barrier-synchronized to maximize overlap: one runs the opportunistic
     * TTL sweep directly (the exact mechanism Task 3 wires into reserveLoanValue), the other
     * calls confirmReservation on the same row.
     *
     * <p>The falsifiable invariant: the sweep's atomic conditional UPDATE (status='RESERVED' ->
     * 'EXPIRED', WHERE status='RESERVED') and confirmReservation's now-locked read-check-write
     * can never BOTH claim to have transitioned this same row -- at most one of
     * "sweep reports 1 row affected" and "confirmReservation returns normally" may be true. Before
     * the fix (plain unlocked findByReservationIdAndTenantId + unconditional save()),
     * confirmReservation's read could observe RESERVED, the sweep could then commit EXPIRED
     * underneath it, and confirmReservation's later unconditional write would silently overwrite
     * that back to CONFIRMED -- both signals true at once, a resurrected reservation. Whichever
     * side "wins" a given run is legitimately nondeterministic (real DB scheduling); what must
     * NEVER happen, on any run, is both winning. */
    @Test
    void confirmReservationAndConcurrentSweepNeverBothClaimTheSameReservation() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"));
        TenantContext.set(tenantId);
        UUID reservationId = policyApi.reserveLoanValue(policyNumber, new BigDecimal("400000"), "TZS", Duration.ofMillis(1));
        Thread.sleep(50); // ttlExpiresAt is now in the past; status is still RESERVED -- nothing has swept it yet.
        TenantContext.clear();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<Integer> sweepFuture = executor.submit(() -> attemptSweep(tenantId, policyNumber, barrier));
        Future<Boolean> confirmFuture = executor.submit(() -> attemptConfirm(tenantId, reservationId, barrier));

        int sweptRows = sweepFuture.get(10, TimeUnit.SECONDS);
        boolean confirmed = confirmFuture.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        boolean sweepExpiredIt = sweptRows == 1;
        assertThat(sweepExpiredIt && confirmed)
            .as("sweep reported expiring the row (sweptRows=%d) AND confirmReservation also reported success (%b) "
                + "-- both cannot be true, or the reservation was silently resurrected", sweptRows, confirmed)
            .isFalse();

        // Durable, DB-level cross-check (not just the Java-level booleans): status and the
        // account's encumbrance must agree with exactly one winner, never a partial/double effect.
        String finalStatus = readReservationStatus(reservationId);
        BigDecimal encumbrance = readEncumbranceAmount(policyNumber);
        if (confirmed) {
            assertThat(finalStatus).isEqualTo("CONFIRMED");
            assertThat(encumbrance).isEqualByComparingTo(new BigDecimal("400000"));
        } else {
            assertThat(finalStatus).isEqualTo("EXPIRED");
            assertThat(encumbrance).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    private int attemptSweep(UUID tenantId, String policyNumber, CyclicBarrier barrier) throws Exception {
        TenantContext.set(tenantId);
        try {
            barrier.await(5, TimeUnit.SECONDS);
            return loanValueReservationRepository.expireStaleReservations(policyNumber, tenantId, java.time.Instant.now());
        } finally {
            TenantContext.clear();
        }
    }

    private boolean attemptConfirm(UUID tenantId, UUID reservationId, CyclicBarrier barrier) throws Exception {
        TenantContext.set(tenantId);
        try {
            barrier.await(5, TimeUnit.SECONDS);
            policyApi.confirmReservation(reservationId);
            return true;
        } catch (InvalidPolicyStateException e) {
            return false;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * M3 final review, I3. {@code reserveLoanValue}'s only amount check was an UPPER bound
     * ({@code amount.compareTo(available) > 0}), which any negative amount passes trivially --
     * so a non-HTTP caller of this published {@code @NamedInterface} (policyloan already calls
     * all four methods; M5's payment integration is documented as the next one) could reach
     * {@code PolicyAccount.increaseEncumbrance} with a negative amount and RAISE the
     * policyholder's own available loan value. Runs against a real policy with a real seeded
     * cash value so the guard is proven to fire BEFORE, not because of, the availability
     * arithmetic.
     */
    @Test
    void reserveLoanValueRejectsANonPositiveAmount() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issuePolicyWithCashValue(tenantId, new BigDecimal("1000000"));
        TenantContext.set(tenantId);

        assertThatThrownBy(() -> policyApi.reserveLoanValue(policyNumber, new BigDecimal("-700000"), "TZS", Duration.ofMinutes(15)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
        assertThatThrownBy(() -> policyApi.reserveLoanValue(policyNumber, BigDecimal.ZERO, "TZS", Duration.ofMinutes(15)))
            .isInstanceOf(IllegalArgumentException.class);

        // Encodes the actual exploit, not merely the exception type: nothing was reserved, and
        // the encumbrance did not move -- so available loan value did not go up.
        assertThat(readEncumbranceAmount(policyNumber)).isEqualByComparingTo("0.00");

        // Control: a positive amount on the same policy still reserves, so the guard is not
        // rejecting everything.
        assertThat(policyApi.reserveLoanValue(policyNumber, new BigDecimal("700000"), "TZS", Duration.ofMinutes(15))).isNotNull();
    }

    private String readReservationStatus(UUID reservationId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT status FROM policy.loan_value_reservation WHERE reservation_id = ?")) {
            statement.setObject(1, reservationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private BigDecimal readEncumbranceAmount(String policyNumber) throws SQLException {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT loan_encumbrance_amount FROM policy.policy_account WHERE policy_number = ?")) {
            statement.setString(1, policyNumber);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBigDecimal(1);
            }
        }
    }
}
