package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceValidationException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyNotFoundException;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;
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
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest(classes = Application.class)
class ReinsuranceApiIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "reinsurance_it_password";

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
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            "db-migrations/reinsurance/V4__projection_product_category.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private ReinsuranceApi reinsuranceApi;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private static ReinsuranceApi.CreateTreatyRequest quotaShare(String percent) {
        return new ReinsuranceApi.CreateTreatyRequest("Africa Re", TreatyType.QUOTA_SHARE,
            new BigDecimal("0.00"), "TZS", new BigDecimal(percent), LocalDate.now().minusMonths(1), null);
    }

    @Test
    void createsATreatyAndItRoundTripsThroughGetTreaty() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        TreatyView created = reinsuranceApi.createTreaty(quotaShare("30.00"), "finance-officer");
        assertThat(created.treatyId()).isNotNull();
        assertThat(created.reinsurerName()).isEqualTo("Africa Re");
        assertThat(created.status()).isEqualTo(TreatyStatus.ACTIVE);
        assertThat(created.cessionPercent()).isEqualByComparingTo("30.00");

        TenantContext.set(tenantId);
        TreatyView reloaded = reinsuranceApi.getTreaty(created.treatyId());
        assertThat(reloaded.treatyId()).isEqualTo(created.treatyId());
        assertThat(reloaded.reinsurerName()).isEqualTo("Africa Re");
    }

    @Test
    void rejectsAQuotaShareTreatyWithNoCessionPercent() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ReinsuranceApi.CreateTreatyRequest request = new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), "TZS",
            null, LocalDate.now(), null);
        assertThrows(ReinsuranceValidationException.class,
            () -> reinsuranceApi.createTreaty(request, "finance-officer"));
    }

    @Test
    void rejectsANonQuotaShareTreatyThatCarriesACessionPercent() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ReinsuranceApi.CreateTreatyRequest request = new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", TreatyType.SURPLUS, new BigDecimal("500000.00"), "TZS",
            new BigDecimal("30.00"), LocalDate.now(), null);
        assertThrows(ReinsuranceValidationException.class,
            () -> reinsuranceApi.createTreaty(request, "finance-officer"));
    }

    @Test
    void rejectsABlankReinsurerName() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ReinsuranceApi.CreateTreatyRequest request = new ReinsuranceApi.CreateTreatyRequest(
            "  ", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), "TZS",
            new BigDecimal("30.00"), LocalDate.now(), null);
        assertThrows(ReinsuranceValidationException.class,
            () -> reinsuranceApi.createTreaty(request, "finance-officer"));
    }

    @Test
    void rejectsAnEffectiveToBeforeEffectiveFrom() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        ReinsuranceApi.CreateTreatyRequest request = new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), "TZS",
            new BigDecimal("30.00"), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1));
        assertThrows(ReinsuranceValidationException.class,
            () -> reinsuranceApi.createTreaty(request, "finance-officer"));
    }

    @Test
    void getTreatyThrowsForAnUnknownId() {
        TenantContext.set(UUID.randomUUID());
        UUID unknown = UUID.randomUUID();
        assertThrows(TreatyNotFoundException.class, () -> reinsuranceApi.getTreaty(unknown));
    }

    /** Not merely an empty list: a treaty genuinely existing in another tenant must be invisible,
     * proven under real RLS with app_role (NOSUPERUSER NOBYPASSRLS). */
    @Test
    void aTreatyIsInvisibleToAnyOtherTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        TenantContext.set(tenantA);
        TreatyView inA = reinsuranceApi.createTreaty(quotaShare("30.00"), "finance-officer");

        TenantContext.set(tenantB);
        assertThrows(TreatyNotFoundException.class, () -> reinsuranceApi.getTreaty(inA.treatyId()));
        assertThat(reinsuranceApi.listTreaties(null)).isEmpty();
    }

    @Test
    void listTreatiesFiltersByStatus() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        reinsuranceApi.createTreaty(quotaShare("30.00"), "finance-officer");

        TenantContext.set(tenantId);
        assertThat(reinsuranceApi.listTreaties(null)).hasSize(1);
        TenantContext.set(tenantId);
        assertThat(reinsuranceApi.listTreaties(TreatyStatus.ACTIVE)).hasSize(1);
        // The falsifiable half -- a filter that ignored its argument would return the row here too.
        TenantContext.set(tenantId);
        assertThat(reinsuranceApi.listTreaties(TreatyStatus.EXPIRED)).isEmpty();
    }
}
