package tz.co.nlolo.lifeplatform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Applies real Flyway migration SQL files (db-migrations/<module>/V1__...sql)
 * directly via JDBC against a Testcontainers Postgres instance, mirroring how
 * .github/workflows/ci-cd.yml's db-migration-validation job and scripts/migrate.sh
 * apply them -- so integration tests run against the SAME schema-creation SQL
 * that ships, not a hand-maintained copy that could drift.
 */
public final class MigrationTestSupport {

    private MigrationTestSupport() {}

    static final String EVENT_PUBLICATION = "db-migrations/audit/V4__event_publication.sql";
    static final String ACTIVITY = "db-migrations/audit/V5__activity.sql";

    public static void applyMigration(String jdbcUrl, String username, String password, String... migrationPaths)
            throws IOException, SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            // Real deployments create app_role via infra/postgres/init/01-create-app-role.sh
            // BEFORE Flyway ever runs (docs/07-infrastructure-architecture.md). Several modules'
            // migrations (e.g. audit's WORM GRANT/REVOKE) reference app_role directly, so a bare
            // Testcontainers Postgres needs the same bootstrap step here, or those statements fail
            // with "role app_role does not exist" -- idempotent so multiple migrationPaths/tests
            // sharing a container don't collide.
            try (Statement bootstrap = connection.createStatement()) {
                bootstrap.execute("DO $$ BEGIN " +
                    "IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'app_role') THEN " +
                    "CREATE ROLE app_role NOLOGIN; END IF; END $$;");
            }
            for (String migrationPath : migrationPaths) {
                String sql = Files.readString(Path.of(migrationPath));
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            // The event publication registry (2026-11-14, durable events): every application context records each
            // after-commit delivery there, so every test database needs it, whichever modules it migrates. Re-runnable.
            try (Statement statement = connection.createStatement()) {
                statement.execute(Files.readString(Path.of(EVENT_PUBLICATION)));
            }
            // The activity log (2026-11-14): AuditLogEntry maps audit_log.action_id, so wherever a test creates the audit
            // schema it gets audit/V5 too -- once, since V5 creates a table.
            try (Statement statement = connection.createStatement();
                 var exists = statement.executeQuery("SELECT to_regclass('audit.audit_log') IS NOT NULL,"
                     + " to_regclass('audit.activity') IS NOT NULL")) {
                exists.next();
                boolean audit = exists.getBoolean(1);
                boolean activity = exists.getBoolean(2);
                if (audit && !activity) {
                    try (Statement v5 = connection.createStatement()) {
                        v5.execute(Files.readString(Path.of(ACTIVITY)));
                    }
                }
            }
        }
    }
}
