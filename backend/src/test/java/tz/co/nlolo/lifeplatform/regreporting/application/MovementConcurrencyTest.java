package tz.co.nlolo.lifeplatform.regreporting.application;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyMovementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M10 FINAL REVIEW, finding C1 -- the falsifiable proof that two concurrent increments of the SAME
 * movement row can no longer lose one silently.
 *
 * <p><b>What was broken.</b> All four listeners maintain their fact table by read-modify-write:
 * {@code findByTenantIdAndPeriod...(...)} -> an in-memory {@code apply*} increment -> {@code save}.
 * With no {@code @Version} on the entity and no {@code version} column on the table, two
 * transactions could both read {@code policies_issued = 0}, both compute 1, and both write 1 --
 * final value 1 where it should be 2. No exception, no failure counter, no alert: just a policy
 * movement absent from every regulatory return generated afterwards.
 *
 * <p><b>Why this test is deterministic rather than hopeful.</b> A vacuous concurrency test -- fire
 * two events and hope they overlap -- would pass with or without the fix, because a find-modify-save
 * over one row finishes in microseconds and two of them almost never interleave by luck. This test
 * instead holds a {@link CyclicBarrier} OPEN BETWEEN THE READ AND THE WRITE: neither transaction may
 * proceed to its {@code save}/commit until BOTH have read the row, so both are guaranteed to have
 * seen {@code version = 0} and the lost-update window is genuinely open every run. Measured before
 * the fix (entities without {@code @Version}, no regreporting/V3): final count 1. After: 2.
 *
 * <p><b>What it exercises.</b> {@code policy_movement} (one fact table is enough to prove the
 * pattern -- all four carry the identical {@code @Version} and all four listeners share the identical
 * {@link ProjectionSupport#withOptimisticLockRetry} wrapper), the REAL production retry helper, and
 * the same {@code PROPAGATION_REQUIRES_NEW} + {@code TenantContext} shape every listener's {@code
 * withTenant} uses. It deliberately does NOT publish domain events: the barrier has to sit inside
 * the transaction, between the read and the write, and no event-driven harness can reach in there.
 * The listeners' own event plumbing is covered by {@code ProjectionEndToEndTest} and {@code
 * MissingDimensionTest}; what is under test here is the concurrency contract underneath it.
 *
 * <p>Real Postgres, real RLS, {@code app_role} (NOSUPERUSER NOBYPASSRLS) -- the same harness shape as
 * {@code RegreportingApiIntegrationTest}. A random tenant, so nothing here depends on the seeded
 * {@code QUARTERLY_PRUDENTIAL} definition.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class MovementConcurrencyTest {

    private static final String APP_ROLE_PASSWORD = "regreporting_movement_concurrency_password";
    private static final String PERIOD = "2026-Q2";
    private static final BigDecimal SUM_ASSURED_EACH = new BigDecimal("1000.00");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
            "db-migrations/regreporting/V1__create_regreporting_schema.sql",
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql",
            "db-migrations/regreporting/V3__optimistic_locking_on_movement_tables.sql",
            "db-migrations/regreporting/V5__member_movement_columns.sql",
            "db-migrations/regreporting/V6__free_look_cancellation_movement.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private PolicyMovementRepository policyMovementRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate requiresNew;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    /** Same shape the listeners build in their constructors: an AFTER_COMMIT projection must run in
     * its own transaction, and each retry attempt needs a fresh one. */
    private TransactionTemplate requiresNew() {
        if (requiresNew == null) {
            requiresNew = new TransactionTemplate(transactionManager);
            requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        }
        return requiresNew;
    }

    @Test
    void twoConcurrentIncrementsOfTheSameMovementRowBothSurvive() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        // The row must already EXIST before the race, so that both attempts contend on an UPDATE
        // (the optimistic-lock path C1 closes) rather than on two INSERTs of the same primary key,
        // which is a different, already-loud failure (duplicate key, not a lost update).
        TenantContext.set(tenantId);
        requiresNew().executeWithoutResult(status ->
            policyMovementRepository.save(new PolicyMovement(tenantId, PERIOD, productId, "TZS")));
        TenantContext.clear();

        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Void>> tasks = List.of(
                () -> incrementIssuedOnce(tenantId, productId, bothHaveRead),
                () -> incrementIssuedOnce(tenantId, productId, bothHaveRead));
            for (Future<Void> future : executor.invokeAll(tasks)) {
                future.get(60, TimeUnit.SECONDS); // rethrows anything either attempt failed with
            }
        } finally {
            executor.shutdownNow();
        }

        TenantContext.set(tenantId);
        Optional<PolicyMovement> reloaded =
            policyMovementRepository.findByTenantIdAndPeriodAndProductId(tenantId, PERIOD, productId);
        assertThat(reloaded).isPresent();

        assertThat(reloaded.get().getPoliciesIssued())
            .as("BOTH increments must be durable. A value of 1 here is the C1 lost update: both "
                + "transactions read 0, both wrote 1, and one policy issuance vanished from every "
                + "return that would have counted it -- silently, with no exception and no counter")
            .isEqualTo(2);
        assertThat(reloaded.get().getSumAssuredIssued())
            .as("the money measure must survive the same way the count does")
            .isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(reloaded.get().getVersion())
            .as("two committed UPDATEs means the optimistic lock advanced twice -- proof the second "
                + "write went through the retry rather than overwriting the first")
            .isGreaterThanOrEqualTo(2L);
    }

    /**
     * One listener-shaped increment: {@code TenantContext} set on THIS worker thread (it is a
     * ThreadLocal -- setting it only on the test's main thread would make every repository call
     * inside the task fail fail-closed instead of racing), the whole transaction wrapped in the real
     * {@link ProjectionSupport#withOptimisticLockRetry}, and the barrier held open between the read
     * and the write so the lost-update window is real rather than hoped for.
     *
     * <p>The barrier is awaited ONLY on the first attempt. On a retry the other thread has already
     * committed and left the barrier for good, so awaiting again would block until timeout and turn
     * a passing fix into a hang.
     */
    private Void incrementIssuedOnce(UUID tenantId, UUID productId, CyclicBarrier bothHaveRead) {
        TenantContext.set(tenantId);
        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        try {
            ProjectionSupport.withOptimisticLockRetry("test.PolicyIssued", () ->
                requiresNew().executeWithoutResult(status -> {
                    PolicyMovement movement = policyMovementRepository
                        .findByTenantIdAndPeriodAndProductId(tenantId, PERIOD, productId)
                        .orElseThrow(() -> new IllegalStateException(
                            "the movement row must have been seeded before the race -- if this "
                            + "throws, the fixture is broken and the test would prove nothing"));
                    if (firstAttempt.compareAndSet(true, false)) {
                        awaitBothReads(bothHaveRead);
                    }
                    movement.applyIssued(SUM_ASSURED_EACH);
                    policyMovementRepository.save(movement);
                }));
        } finally {
            TenantContext.clear();
        }
        return null;
    }

    private static void awaitBothReads(CyclicBarrier bothHaveRead) {
        try {
            bothHaveRead.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("both transactions must reach the barrier having read the "
                + "row; if they did not, the race window never opened and this test proves nothing", e);
        }
    }
}
