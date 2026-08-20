package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingApi;
import tz.co.nlolo.lifeplatform.regreporting.api.RegulatoryReturnView;
import tz.co.nlolo.lifeplatform.regreporting.api.ReturnLineView;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyMovementRepository;
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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6, Step 5 -- the end-to-end counterpart to {@code CumulativeMetricTest}'s unit proof (Task
 * 4), against a REAL database rather than hand-built in-memory rows: three consecutive quarters of
 * {@code policy_movement}, seeded directly via the repository (this step is deliberately NOT about
 * the listeners -- Steps 2 and 4 already proved those), so that a real {@code generateReturn} call
 * for the EARLIEST period reports THAT period's own figure, never the latest. This is what proves
 * the movements-vs-snapshot design decision actually holds once {@code MetricReaderRegistry}'s
 * SQL-backed repository reads -- not just its static arithmetic helpers -- are in the loop.
 *
 * <p>Same Testcontainers/{@code app_role} harness as {@code RegreportingApiIntegrationTest}; uses
 * SEEDED_TENANT because {@code QUARTERLY_PRUDENTIAL} is only seeded for it (regreporting/V2
 * section 9).
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class HistoricalPeriodReturnTest {

    private static final String APP_ROLE_PASSWORD = "regreporting_historical_period_password";
    private static final UUID SEEDED_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

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
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private RegreportingApi regreportingApi;
    @Autowired private PolicyMovementRepository policyMovementRepository;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    /** Same shape as {@code CumulativeMetricTest.movement} but persisted through the real
     * repository against the real table, rather than left as an in-memory object. */
    private void seedMovement(UUID productId, String period, int issued, int lapsed) {
        TenantContext.set(SEEDED_TENANT);
        PolicyMovement movement = new PolicyMovement(SEEDED_TENANT, period, productId, "TZS");
        for (int i = 0; i < issued; i++) movement.applyIssued(new BigDecimal("100000.00"));
        for (int i = 0; i < lapsed; i++) movement.applyLapsed(new BigDecimal("100000.00"));
        policyMovementRepository.save(movement);
    }

    private static ReturnLineView lineFor(RegulatoryReturnView view, String metricName) {
        return view.lines().stream().filter(l -> metricName.equals(l.metricName())).findFirst()
            .orElseThrow(() -> new AssertionError("Expected a " + metricName + " line on the generated return"));
    }

    @Test
    void aReturnGeneratedForAnEarlierPeriodReportsThatPeriodsOwnFigureNotTheLatestsCumulative() {
        UUID productId = UUID.randomUUID();

        // 2026-Q1: 10 issued, none lapsed -- cumulative in-force = 10.
        seedMovement(productId, "2026-Q1", 10, 0);
        // 2026-Q2: 5 more issued, 2 lapsed -- cumulative in-force = 10 + 5 - 2 = 13.
        seedMovement(productId, "2026-Q2", 5, 2);
        // 2026-Q3: 8 more issued, 1 lapsed -- cumulative in-force = 13 + 8 - 1 = 20.
        seedMovement(productId, "2026-Q3", 8, 1);

        // ---- Generate for the EARLIEST period: must report 10, Q1's own figure, ignoring the
        // Q2/Q3 rows entirely (a regression to a running/latest-wins counter would report 20 here
        // instead -- exactly the bug this test exists to catch). ----
        TenantContext.set(SEEDED_TENANT);
        RegulatoryReturnView q1Return = regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", "2026-Q1", "historical-tester");
        BigDecimal q1PoliciesInForce = lineFor(q1Return, "POLICIES_IN_FORCE").numericValue();
        assertThat(q1PoliciesInForce)
            .as("2026-Q1 must report ITS OWN figure -- 10 issued, nothing lapsed yet, Q2/Q3 ignored")
            .isEqualByComparingTo("10");

        // ---- Generate for the LATEST period: the cumulative figure must differ from Q1's,
        // proving the STOCK metric genuinely accumulates rather than reading the same value twice. ----
        TenantContext.set(SEEDED_TENANT);
        RegulatoryReturnView q3Return = regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", "2026-Q3", "historical-tester");
        BigDecimal q3PoliciesInForce = lineFor(q3Return, "POLICIES_IN_FORCE").numericValue();
        assertThat(q3PoliciesInForce)
            .as("2026-Q3 must report the full cumulative figure across all three periods")
            .isEqualByComparingTo("20");
        assertThat(q3PoliciesInForce)
            .as("the cumulative figure must genuinely differ between an earlier and a later period")
            .isNotEqualByComparingTo(q1PoliciesInForce);
    }
}
