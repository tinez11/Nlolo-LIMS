package tz.co.nlolo.lifeplatform.regreporting.application;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingApi;
import tz.co.nlolo.lifeplatform.regreporting.api.RegreportingValidationException;
import tz.co.nlolo.lifeplatform.regreporting.api.RegulatoryReturnView;
import tz.co.nlolo.lifeplatform.regreporting.api.ReturnNotFoundException;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real Postgres, real RLS, {@code app_role} (NOSUPERUSER NOBYPASSRLS) -- exactly the harness
 * shape {@code reinsurance.ReinsuranceApiIntegrationTest} uses. Migration list is short on
 * purpose: unlike M9's {@code finaccounting}, this module has no partitioned table, so there is
 * no {@code policyloan/V2}-equivalent partition-control trigger to include here.
 *
 * <p>{@code QUARTERLY_PRUDENTIAL} is seeded (regreporting/V2 section 9) for exactly ONE tenant,
 * {@code 11111111-1111-1111-1111-111111111111} -- so every test that generates against that
 * return type runs as that tenant. Tests only care about isolation between an unrelated SECOND
 * tenant use a fresh random UUID for tenant B, which never has any definition seeded for it (not
 * needed -- tenant B never calls generateReturn in these tests, only getReturn/listReturns).
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class RegreportingApiIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "regreporting_it_password";
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
            "db-migrations/regreporting/V2__grants_rls_dimensions_movements_and_return_lines.sql",
            "db-migrations/regreporting/V3__optimistic_locking_on_movement_tables.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private RegreportingApi regreportingApi;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void generatesTenReturnLinesReadableBackInLineNoOrder() {
        TenantContext.set(SEEDED_TENANT);

        RegulatoryReturnView generated = regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", "2026-Q3", "tester");
        assertThat(generated.returnId()).isNotNull();
        assertThat(generated.returnType()).isEqualTo("QUARTERLY_PRUDENTIAL");
        assertThat(generated.period()).isEqualTo("2026-Q3");
        assertThat(generated.status()).isEqualTo("READY");
        assertThat(generated.documentRef()).isNull();
        assertThat(generated.generatedBy()).isEqualTo("tester");
        assertThat(generated.lines()).hasSize(10);

        TenantContext.set(SEEDED_TENANT);
        RegulatoryReturnView reloaded = regreportingApi.getReturn(generated.returnId());
        assertThat(reloaded.lines()).hasSize(10);
        // line_no order, not insertion order or line_code order.
        for (int i = 0; i < reloaded.lines().size(); i++) {
            assertThat(reloaded.lines().get(i).lineNo()).isEqualTo(i + 1);
        }
    }

    /** THE regeneration test: calling generateReturn twice for the same (tenant, returnType,
     * period) must reuse the same regulatory_return row and replace its lines, never accumulate a
     * second return or twenty lines. */
    @Test
    void regeneratingTheSameReturnReplacesRatherThanDuplicates() {
        TenantContext.set(SEEDED_TENANT);
        RegulatoryReturnView first = regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", "2026-Q1", "first-run");

        TenantContext.set(SEEDED_TENANT);
        RegulatoryReturnView second = regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", "2026-Q1", "second-run");

        assertThat(second.returnId()).isEqualTo(first.returnId());
        assertThat(second.generatedBy()).isEqualTo("second-run");
        assertThat(second.generatedAt()).isAfterOrEqualTo(first.generatedAt());
        assertThat(second.lines()).hasSize(10);

        TenantContext.set(SEEDED_TENANT);
        assertThat(regreportingApi.listReturns("2026-Q1")).hasSize(1);
    }

    @Test
    void anUnknownReturnTypeThrowsRegreportingValidationException() {
        TenantContext.set(SEEDED_TENANT);
        assertThrows(RegreportingValidationException.class,
            () -> regreportingApi.generateReturn("NO_SUCH_RETURN_TYPE", "2026-Q2", "tester"));
    }

    /** QUARTERLY_PRUDENTIAL's period_kind is QUARTERLY (regreporting/V2 section 9) -- an annual
     * period must never be accepted for it, because that would let a quarterly and an annual
     * period be cumulative-summed together as if comparable. */
    @Test
    void anAnnualPeriodIsRejectedForTheQuarterlySeededDefinition() {
        TenantContext.set(SEEDED_TENANT);
        assertThrows(RegreportingValidationException.class,
            () -> regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", "2026", "tester"));
    }

    @Test
    void getReturnThrowsForAnUnknownId() {
        TenantContext.set(UUID.randomUUID());
        UUID unknown = UUID.randomUUID();
        assertThrows(ReturnNotFoundException.class, () -> regreportingApi.getReturn(unknown));
    }

    /** Not merely an empty list: a return genuinely existing for another tenant must be invisible,
     * proven under real RLS with app_role (NOSUPERUSER NOBYPASSRLS) -- the exact same proof shape
     * {@code ReinsuranceApiIntegrationTest.aTreatyIsInvisibleToAnyOtherTenant} uses. */
    @Test
    void getReturnThrowsForAReturnBelongingToAnotherTenant() {
        TenantContext.set(SEEDED_TENANT);
        RegulatoryReturnView inSeededTenant =
            regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", "2026-Q4", "tester");

        UUID otherTenant = UUID.randomUUID();
        TenantContext.set(otherTenant);
        assertThrows(ReturnNotFoundException.class, () -> regreportingApi.getReturn(inSeededTenant.returnId()));
        assertThat(regreportingApi.listReturns(null)).isEmpty();
    }

    @Test
    void listReturnsWithNoFilterReturnsEveryReturnAndWithAPeriodFilterGenuinelyNarrows() {
        TenantContext.set(SEEDED_TENANT);
        regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", "2026-Q1", "tester");
        TenantContext.set(SEEDED_TENANT);
        regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", "2026-Q2", "tester");

        TenantContext.set(SEEDED_TENANT);
        List<RegulatoryReturnView> all = regreportingApi.listReturns(null);
        assertThat(all.size()).isGreaterThanOrEqualTo(2);
        assertThat(all).extracting(RegulatoryReturnView::period).contains("2026-Q1", "2026-Q2");

        TenantContext.set(SEEDED_TENANT);
        List<RegulatoryReturnView> narrowed = regreportingApi.listReturns("2026-Q1");
        assertThat(narrowed).extracting(RegulatoryReturnView::period).containsOnly("2026-Q1");
        // The falsifiable half -- a filter that ignored its argument would return Q2 here too.
        assertThat(narrowed).extracting(RegulatoryReturnView::period).doesNotContain("2026-Q2");
    }

    /** No projections exist anywhere for this period, so every one of the ten seeded lines reads
     * as a genuine zero (STOCK metrics read an empty cumulative window; FLOW metrics read an
     * empty period) -- the return still generates successfully rather than failing on absent
     * data. */
    @Test
    void everyLineReadsAsZeroWhenNoProjectionsExist() {
        TenantContext.set(SEEDED_TENANT);
        RegulatoryReturnView generated = regreportingApi.generateReturn("QUARTERLY_PRUDENTIAL", "2099-Q1", "tester");

        assertThat(generated.lines()).hasSize(10);
        assertThat(generated.lines())
            .allSatisfy(line -> assertThat(line.numericValue()).isEqualByComparingTo("0"));
    }
}
