package tz.co.nlolo.lifeplatform;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Sets the Postgres session variable app.current_tenant_id on every
 * connection this application acquires, read from TenantContext -- the
 * mechanism docs/06-database-schema.md §5 describes as "typically via a
 * connection-acquisition interceptor." Every RLS policy in every module's
 * migration depends on this being set before that connection issues any
 * query.
 *
 * Uses getOrNull(), not get(): connections acquired outside any
 * tenant-scoped operation (actuator health checks, boot-time Hibernate
 * metadata validation, refdata's non-tenant-scoped queries) must not crash --
 * they simply don't get the session variable set, which means RLS-protected
 * tables show zero rows to that connection (fail-closed, never fail-open).
 *
 * String concatenation into the SET statement is safe here specifically
 * because TenantContext only ever holds a UUID -- UUID#toString() can only
 * ever produce its fixed 36-character hex-and-dash form, so there is no
 * free-text injection surface. This would NOT be safe with an untyped
 * identifier.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenantContext(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenantContext(super.getConnection(username, password));
    }

    private Connection applyTenantContext(Connection connection) throws SQLException {
        UUID tenantId = TenantContext.getOrNull();
        if (tenantId != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET app.current_tenant_id = '" + tenantId + "'");
            }
        }
        return connection;
    }
}
