package tz.co.nlolo.lifeplatform.regreporting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyMovementRepository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a free-look cancellation appears in the regulatory return.
 *
 * <p>It is the one termination-shaped event that is NOT a termination. The customer exercised a
 * statutory right inside the cooling-off window and the contract is void from inception, so the
 * issuance is REVERSED out of the cohort it was counted in rather than a termination being added
 * beside it. Reporting it as written-then-terminated would inflate gross new business and gross
 * terminations together, and compute persistency over a policy that in law never existed.
 *
 * <p>The second test is the one that matters most: a cancellation in a LATER quarter than the
 * issuance must still land on the issuance cohort's row. That is only possible because
 * {@code handlePolicyActivated} keys its movement on the issue date and stores that date on the
 * dimension, so the cohort is recoverable from stored data rather than from the event.
 *
 * <p>Real Postgres, real RLS, {@code app_role} (NOSUPERUSER NOBYPASSRLS), and every envelope
 * published for real through {@code ApplicationEventPublisher} -- the harness shape
 * {@code MissingDimensionTest} uses, and for its reason: the whole AFTER_COMMIT chain has to run.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class FreeLookMovementTest {

    private static final String APP_ROLE_PASSWORD = "regreporting_free_look_password";
    private static final BigDecimal SUM_ASSURED = new BigDecimal("2000000.00");

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
            "db-migrations/regreporting/V1__create_regreporting_schema.sql",
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql",
            "db-migrations/regreporting/V3__optimistic_locking_on_movement_tables.sql",
            "db-migrations/regreporting/V5__member_movement_columns.sql",
            "db-migrations/regreporting/V6__free_look_cancellation_movement.sql",
            "db-migrations/regreporting/V7__scheme_may_open_empty.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PolicyMovementRepository policyMovementRepository;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetAfterEach() { TenantContext.clear(); }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    /** The listener's own quarter arithmetic, duplicated rather than reaching into a
     *  package-private production helper -- so this asserts the observable period, not the code
     *  that computed it. */
    private static String quarterOf(LocalDate date) {
        return date.getYear() + "-Q" + ((date.getMonthValue() - 1) / 3 + 1);
    }

    private void publish(UUID tenantId, String eventType, Map<String, Object> payload) {
        var envelope = DomainEventEnvelope.of(eventType, tenantId, payload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));
    }

    /** `policy.policy_number` is VARCHAR(20) and `policy_dimension` mirrors it, so a fixture
     *  number has to be the real shape -- a long one inserts fine nowhere. */
    private static String newPolicyNumber() {
        return "POL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * The payload `PolicyApiImpl.publishPolicyActivated` really sends, not a minimal one.
     *
     * <p>Every key is here even though this module reads four of them: other listeners consume the
     * same envelope, and a fixture that omits what they read makes them throw into their own
     * catch blocks -- noise that looks exactly like a real failure to whoever reads the log next.
     */
    private void activate(UUID tenantId, UUID productId, String policyNumber, LocalDate issueDate) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", UUID.randomUUID());
        payload.put("productId", productId);
        payload.put("productVersionId", UUID.randomUUID());
        payload.put("sumAssured", Map.of("amount", SUM_ASSURED.toPlainString(), "currencyCode", "TZS"));
        payload.put("premium", Map.of("amount", "50000.00", "currencyCode", "TZS"));
        payload.put("premiumFrequency", "MONTHLY");
        payload.put("issueDate", issueDate.toString());
        payload.put("agentOfRecordId", null);
        payload.put("activatedAt", LocalDate.now().toString());
        payload.put("productCategory", "TERM_LIFE");
        publish(tenantId, "policy.PolicyActivated", payload);
    }

    private void cancelInFreeLook(UUID tenantId, String policyNumber) {
        publish(tenantId, "policy.PolicyCancelledFreeLook", Map.of(
            "policyNumber", policyNumber,
            "cancelledAt", Instant.now().toString(),
            "cancelledBy", "fin-2"));
    }

    private Optional<PolicyMovement> movement(UUID tenantId, String period, UUID productId) {
        TenantContext.set(tenantId);
        return policyMovementRepository.findByTenantIdAndPeriodAndProductId(tenantId, period, productId);
    }

    @Test
    void theIssuanceIsReversedOutOfTheCohortAndNoTerminationIsRecorded() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String policyNumber = newPolicyNumber();
        LocalDate issueDate = LocalDate.now();

        activate(tenantId, productId, policyNumber, issueDate);
        PolicyMovement afterIssue = movement(tenantId, quarterOf(issueDate), productId).orElseThrow();
        assertThat(afterIssue.getPoliciesIssued()).isEqualTo(1);
        assertThat(afterIssue.getSumAssuredIssued()).isEqualByComparingTo(SUM_ASSURED);

        cancelInFreeLook(tenantId, policyNumber);

        PolicyMovement after = movement(tenantId, quarterOf(issueDate), productId).orElseThrow();
        // The cohort nets to nothing: in law this contract was never written.
        assertThat(after.getPoliciesIssued())
            .as("a free-look cancellation removes the policy from new business entirely").isZero();
        assertThat(after.getSumAssuredIssued()).isEqualByComparingTo(BigDecimal.ZERO);
        // And is NOT reported as a termination -- doing both would inflate written AND terminated,
        // and compute persistency over a policy that never existed.
        assertThat(after.getSumAssuredTerminated()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(after.getPoliciesLapsed()).isZero();
        assertThat(after.getPoliciesMatured()).isZero();
        assertThat(after.getPoliciesClaimTerminated()).isZero();
        // The cause is visible, so a shrunken quarter can be reconciled rather than merely noticed.
        assertThat(after.getPoliciesCancelledFreeLook()).isEqualTo(1);
    }

    @Test
    void aCancellationInALaterQuarterStillLandsOnTheIssuanceCohort() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String policyNumber = newPolicyNumber();
        // Issued in a prior quarter, cancelled now. A free-look window runs up to 365 days, so this
        // is reachable -- and it is the case the treatment turns on.
        LocalDate issueDate = LocalDate.now().minusMonths(4);
        String issuanceQuarter = quarterOf(issueDate);
        String currentQuarter = quarterOf(LocalDate.now());
        assertThat(issuanceQuarter).isNotEqualTo(currentQuarter);

        activate(tenantId, productId, policyNumber, issueDate);
        cancelInFreeLook(tenantId, policyNumber);

        // The reversal lands where the issuance was counted, which it can only do because the
        // period comes from the dimension's stored issue date rather than from the event.
        PolicyMovement cohort = movement(tenantId, issuanceQuarter, productId).orElseThrow();
        assertThat(cohort.getPoliciesIssued()).isZero();
        assertThat(cohort.getSumAssuredIssued()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cohort.getPoliciesCancelledFreeLook()).isEqualTo(1);

        // And the quarter the customer happened to cancel in is untouched. Writing the reversal
        // here instead would understate a quarter that never counted the policy.
        assertThat(movement(tenantId, currentQuarter, productId))
            .as("the cancellation's own quarter must carry no movement at all").isEmpty();
    }

    @Test
    void aPolicyWhoseIssuanceWasNeverProjectedReversesNothing() {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = newPolicyNumber();

        // No dimension row, so the issuance was never counted either. Unlike a termination this
        // must NOT fall back to the UNKNOWN sentinel: decrementing a row nothing incremented would
        // breach the non-negative CHECK and invent an understatement out of nothing.
        cancelInFreeLook(tenantId, policyNumber);

        assertThat(movement(tenantId, quarterOf(LocalDate.now()), new UUID(0L, 0L)))
            .as("no sentinel movement -- there is nothing to reverse").isEmpty();
    }
}
