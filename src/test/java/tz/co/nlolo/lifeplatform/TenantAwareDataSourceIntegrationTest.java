package tz.co.nlolo.lifeplatform;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves TenantAwareDataSource does not leak a previous borrower's tenant
 * session variable to the next borrower of the SAME pooled physical
 * connection -- the exact bug class RLS is meant to backstop, but which RLS
 * itself cannot catch if the leaked session variable happens to belong to a
 * REAL tenant (RLS would then simply, silently, wrongly authorize that
 * tenant's rows for a connection that should have seen none).
 *
 * Uses a HikariDataSource capped at maximumPoolSize(1) so the second
 * getConnection() call is guaranteed to hand back the exact same physical
 * connection the first call used (once returned to the pool) -- proving this
 * is about connection-pool session-state hygiene, not merely "two different
 * connections happened to behave independently."
 */
@Testcontainers
class TenantAwareDataSourceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static HikariDataSource pooledDataSource;
    static TenantAwareDataSource tenantAwareDataSource;

    @BeforeAll
    static void setUpPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        pooledDataSource = new HikariDataSource(config);
        tenantAwareDataSource = new TenantAwareDataSource(pooledDataSource);
    }

    @AfterAll
    static void tearDownPool() {
        pooledDataSource.close();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void borrowingWithNoTenantContextClearsAPreviousBorrowersSessionVariable() throws Exception {
        UUID tenantA = UUID.randomUUID();

        // First borrow: tenant A's context is set, TenantAwareDataSource issues
        // SET app.current_tenant_id, and the connection is returned to the
        // single-connection pool still carrying that session variable.
        TenantContext.set(tenantA);
        try (Connection first = tenantAwareDataSource.getConnection();
             Statement statement = first.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT current_setting('app.current_tenant_id', true)")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo(tenantA.toString());
        }

        // Second borrow: no tenant context set at all -- e.g. a health check,
        // a background job, or exactly the application bug RLS exists to catch.
        // With maximumPoolSize(1), this MUST be the same physical connection the
        // first borrow used. Before the RESET fix, this would still report
        // tenant A's UUID here (the leak); after the fix, the session variable
        // is cleared back to unset.
        TenantContext.clear();
        try (Connection second = tenantAwareDataSource.getConnection();
             Statement statement = second.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT current_setting('app.current_tenant_id', true)")) {
            assertThat(resultSet.next()).isTrue();
            String leftoverTenantSetting = resultSet.getString(1);
            assertThat(leftoverTenantSetting == null || leftoverTenantSetting.isEmpty())
                    .as("app.current_tenant_id must not still carry the previous borrower's "
                            + "tenant id ('%s') -- it must be reset, not merely left untouched",
                            tenantA)
                    .isTrue();
        }
    }
}
