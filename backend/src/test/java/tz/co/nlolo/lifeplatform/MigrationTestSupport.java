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
        }
    }
}
