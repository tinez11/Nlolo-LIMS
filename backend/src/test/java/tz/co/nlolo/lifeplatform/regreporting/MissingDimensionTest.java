package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.domain.ClaimsMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ClaimsMovementRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyMovementRepository;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6, Step 2 -- the falsifiable proof that a dimension-lookup MISS never drops a movement.
 *
 * <p>{@code policy.PolicyLapsed} for a policy number that was never issued (so no {@code
 * policy_dimension} row exists) must still write a {@code policy_movement} row, attributed to the
 * {@code UUID(0,0)} sentinel product; {@code claims.ClaimSettled} for a claim id that was never
 * registered must still write a {@code claims_movement} row, attributed to {@code claim_type =
 * 'UNKNOWN'}. Both envelopes are published for real through {@code ApplicationEventPublisher}
 * (never hand-inserted rows), so the whole {@code @TransactionalEventListener(AFTER_COMMIT)} ->
 * {@code withTenant} -> handler chain runs exactly as it would for a real out-of-order or
 * cross-module delivery. See {@code PolicyEventListener}/{@code ClaimsEventListener}'s own javadoc
 * for why "never skip the movement" is the point.
 *
 * <p>Real Postgres, real RLS, {@code app_role} (NOSUPERUSER NOBYPASSRLS) -- the exact harness shape
 * {@code RegreportingApiIntegrationTest} (Task 5) uses.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class MissingDimensionTest {

    private static final String APP_ROLE_PASSWORD = "regreporting_missing_dim_password";

    /** Mirrors ProjectionSupport.UNKNOWN_PRODUCT, which is package-private to
     * regreporting.application -- this test asserts the observable contract (the sentinel value
     * itself, {@code UUID(0,0)}), not an internal implementation detail. */
    private static final UUID UNKNOWN_PRODUCT = new UUID(0L, 0L);
    private static final String UNKNOWN_CLAIM_TYPE = "UNKNOWN";

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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/regreporting/V1__create_regreporting_schema.sql",
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql",
            "db-migrations/regreporting/V3__optimistic_locking_on_movement_tables.sql",
            "db-migrations/regreporting/V5__member_movement_columns.sql");
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
    @Autowired private ClaimsMovementRepository claimsMovementRepository;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetAfterEach() { TenantContext.clear(); }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    /** Same quarter arithmetic as {@code ProjectionSupport.quarterOfInstant} (UTC) -- duplicated
     * here deliberately rather than reaching into a package-private production helper, so this
     * test exercises the listener's real period derivation rather than assuming access to it. */
    private static String currentQuarterUtc() {
        LocalDate date = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
        int quarter = (date.getMonthValue() - 1) / 3 + 1;
        return date.getYear() + "-Q" + quarter;
    }

    @Test
    void aPolicyLapsedForAPolicyNeverIssuedStillWritesAMovementUnderTheUnknownProductSentinel() {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = "MISSING-DIM-POLICY-" + UUID.randomUUID();
        String period = currentQuarterUtc();

        var envelope = DomainEventEnvelope.of("policy.PolicyLapsed", tenantId,
            Map.of("policyNumber", policyNumber, "lapsedAt", Instant.now().toString()));
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        TenantContext.set(tenantId);
        Optional<PolicyMovement> movement =
            policyMovementRepository.findByTenantIdAndPeriodAndProductId(tenantId, period, UNKNOWN_PRODUCT);
        assertThat(movement)
            .as("a policy_movement row must exist for the UNKNOWN_PRODUCT sentinel even though "
                + "policy %s was never issued", policyNumber)
            .isPresent();
        assertThat(movement.get().getPoliciesLapsed())
            .as("the lapse figure must survive rather than vanish").isEqualTo(1);
        assertThat(movement.get().getPoliciesLapsed()).isNotZero();
    }

    @Test
    void aClaimSettledForAClaimNeverRegisteredStillWritesAMovementUnderTheUnknownClaimTypeSentinel() {
        UUID tenantId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        String period = currentQuarterUtc();

        var envelope = DomainEventEnvelope.of("claims.ClaimSettled", tenantId,
            Map.of("claimId", claimId,
                   "policyNumber", "MISSING-DIM-POLICY-FOR-CLAIM",
                   "settledAmount", Map.of("amount", "2000000.00", "currencyCode", "TZS"),
                   "settledAt", Instant.now().toString()));
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        TenantContext.set(tenantId);
        Optional<ClaimsMovement> movement = claimsMovementRepository
            .findByTenantIdAndPeriodAndClaimType(tenantId, period, UNKNOWN_CLAIM_TYPE);
        assertThat(movement)
            .as("a claims_movement row must exist under claim_type='UNKNOWN' even though claim %s "
                + "was never registered", claimId)
            .isPresent();
        assertThat(movement.get().getSettledCount()).isEqualTo(1);
        assertThat(movement.get().getSettledAmount()).isEqualByComparingTo("2000000.00");
    }
}
