package tz.co.nlolo.lifeplatform.audit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.infrastructure.DomainEventAuditListener;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A redelivered event still reaches the event history (2026-11-16, found in the dev backend's log). The ten-minute
 * redelivery runs on the scheduler thread, where no tenant is set; as the application's own role, which row-level
 * security binds, the line and its dead letter were both refused, the error was swallowed, and the delivery was marked
 * complete -- the line lost for good. The owning superuser bypasses RLS, so this class connects as app_role.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class AuditRedeliveryRowSecurityTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String APP_ROLE_PASSWORD = "audit_redelivery_password";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/audit/V4__event_publication.sql",
            "db-migrations/audit/V5__activity.sql");
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private DomainEventAuditListener listener;

    @Test
    void anEventDeliveredWithNoTenantOnTheThreadIsStillRecordedUnderItsOwnTenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        TenantContext.clear();   // as on the scheduler thread that redelivers

        listener.onDomainEvent(new DomainEventEnvelope<>(eventId, "test.Redelivered", 1, tenant, Instant.now(), null,
            Map.of("policyNumber", "POL-REDELIVERED")));

        assertThat(count("SELECT count(*) FROM audit.audit_log WHERE event_id = ? AND tenant_id = ?", eventId, tenant))
            .as("the line is written under the event's tenant").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit.failed_event WHERE event_id = ?", eventId, null)).isZero();
        assertThat(TenantContext.getOrNull()).as("the thread is left as it was found").isNull();
    }

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long count(String sql, UUID first, UUID second) throws Exception {
        try (Connection c = owner(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, first);
            if (second != null) p.setObject(2, second);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }
}
