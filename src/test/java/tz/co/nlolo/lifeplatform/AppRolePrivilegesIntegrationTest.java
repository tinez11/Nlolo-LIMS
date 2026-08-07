package tz.co.nlolo.lifeplatform;

import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every other integration test in this suite (PartyApiIntegrationTest,
 * RowLevelSecurityIntegrationTest, etc.) points spring.datasource.username at
 * the Testcontainers Postgres superuser -- the owner of every table these
 * migrations create -- so none of them can ever notice that the application's
 * REAL runtime identity, app_role (infra/postgres/init/01-create-app-role.sql.template,
 * wired via SPRING_DATASOURCE_USERNAME in infra/docker-compose.yml), had no
 * privileges at all on party/document/refdata/audit until this fix: migrations
 * run as the postgres superuser (scripts/migrate.sh), which becomes owner of
 * every schema/table, and the only grant that previously existed anywhere was
 * scoped to schema public.
 *
 * <p>This test closes that blind spot by pointing the application's OWN
 * Spring-managed DataSource (spring.datasource.username/password, not a
 * hand-rolled side connection) at app_role, then performing a genuine business
 * operation through PartyApi -- proving the identity the app actually runs as
 * in every real deployment can reach its own tables. Before the migration
 * grants were added this failed with "permission denied for schema party";
 * this test is the regression guard against that ever silently coming back.
 *
 * <p>App_role bootstrap mirrors RowLevelSecurityIntegrationTest exactly: ALTER
 * (not CREATE) because MigrationTestSupport.applyMigration already bootstraps
 * a bare NOLOGIN app_role idempotently so the GRANT statements inside the
 * migrations themselves have a role to target; this upgrades it to a real
 * LOGIN role with the NOSUPERUSER NOBYPASSRLS attributes production requires
 * (either one missing silently voids every RLS policy platform-wide).
 *
 * <p>Ordering this relies on: @Testcontainers starts POSTGRES via its
 * BeforeAllCallback before this class's own static @BeforeAll runs, and
 * Spring's ApplicationContext (and therefore the real DataSource bean built
 * from the @DynamicPropertySource-registered app_role credentials) is not
 * created until the first test method's instance is prepared -- i.e. strictly
 * after @BeforeAll has applied migrations and elevated app_role to LOGIN. If
 * that ordering ever changed, this test would fail with an authentication
 * error rather than a false pass, so it is not a silent hazard.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class AppRolePrivilegesIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "test_app_role_password";

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // The point of this test: the application connects as app_role, its actual
        // runtime identity in every real deployment -- never the Testcontainers
        // superuser every other integration test in this suite uses.
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired
    private PartyApi partyApi;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void appRoleCanRegisterAndReadBackAPartyThroughTheApplicationsOwnDataSource() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        Instant before = Instant.now();

        PartyView registered = partyApi.registerIndividual("Neema Kileo", LocalDate.of(1993, 4, 18),
            "+255713000000", "neema@example.tz", "test-agent");

        assertThat(registered.partyId()).isNotNull();

        // Round trip through app_role, not merely the write succeeding: proves
        // SELECT (not just INSERT) is actually granted on party.party too.
        PartyView fetched = partyApi.getParty(registered.partyId());
        assertThat(fetched.displayName()).isEqualTo("Neema Kileo");
        assertThat(fetched.partyId()).isEqualTo(registered.partyId());

        // The AFTER_COMMIT audit listener also writes through app_role's grants
        // (audit.audit_log's USAGE + INSERT/SELECT) -- confirms Part A's audit
        // grants as well, not only party's, in the same round trip.
        List<?> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "party.PartyRegistered", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
    }
}
