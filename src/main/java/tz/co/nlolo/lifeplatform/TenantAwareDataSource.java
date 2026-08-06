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
 * MUST explicitly RESET app.current_tenant_id when no tenant context is set,
 * rather than merely skipping the SET: under connection pooling (HikariCP),
 * SET is session-scoped and persists on the physical connection across
 * borrow/return cycles. Without the RESET, a connection last used by tenant
 * A's thread and returned to the pool would still carry tenant A's session
 * variable when a later borrower with NO tenant context (or a different
 * tenant, before its own SET runs) queries through it -- silently leaking
 * tenant A's rows instead of failing closed. The RESET makes every borrow's
 * starting session state deterministic regardless of the previous borrower.
 *
 * SET is safe to skip and RESET issued for every connection lacking a
 * tenant context (even a fresh, never-before-used one) -- current_setting on
 * a not-yet-set app.current_tenant_id already returns NULL, and RESET on it
 * is a no-op in that case, so this is not merely a pooled-connection special
 * case but the routine, both-cases-covered path.
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
        try (Statement statement = connection.createStatement()) {
            if (tenantId != null) {
                statement.execute("SET app.current_tenant_id = '" + tenantId + "'");
            } else {
                // Explicit RESET, not a no-op skip: a pooled physical connection may
                // still carry a PREVIOUS borrower's SET from before it was returned
                // to the pool. Without this, that stale value would leak into this
                // borrower's queries instead of leaving RLS to fail closed.
                statement.execute("RESET app.current_tenant_id");
            }
        }
        return connection;
    }
}
