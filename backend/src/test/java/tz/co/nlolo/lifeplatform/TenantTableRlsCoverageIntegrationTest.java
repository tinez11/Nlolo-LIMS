package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every table carrying a {@code tenant_id} enforces row-level security. Asked of the catalog, not
 * of a list.
 *
 * <p>WHY THIS CLASS EXISTS. {@code underwriting.proposal_beneficiary} shipped in V6 with a
 * {@code tenant_id UUID NOT NULL} and no policy, and survived five milestones. Nothing was
 * negligent about that: the table was created by hand, the policies are written by hand, and
 * {@link RowLevelSecurityIntegrationTest} enumerates by hand which tables to prove isolated. Three
 * hand-maintained lists and no cross-check between them, so a table missing from all three is
 * invisible from all three. Adding one more hand-written case would have fixed that table and left
 * the next one exposed.
 *
 * <p>WHY IT APPLIES EVERY MIGRATION. The two existing integration tests that touch the catalog each
 * apply a hand-picked subset -- 81 and roughly 90 of the 130 migration files. A coverage sweep run
 * against a subset only covers the tables somebody remembered to list, which is the same blind spot
 * one level up. This discovers every {@code db-migrations/<module>/V*.sql} and applies them in the
 * order {@code scripts/migrate.sh} applies them, reading the module order out of that script rather
 * than keeping a second copy of it here.
 *
 * <p>WHY NO SPRING. This needs a Postgres and a {@link Statement}. A {@code @SpringBootTest} would
 * add its own context to a suite where 85 classes already each start one, for nothing this assertion
 * uses.
 */
@Testcontainers
class TenantTableRlsCoverageIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /**
     * Every table on the platform that declares a tenant_id, with whether RLS is switched on and
     * how many policies are attached. Both halves are load-bearing and neither implies the other:
     * {@code ENABLE ROW LEVEL SECURITY} with no policy denies everything to a non-owner, and a
     * policy on a table without it is inert.
     */
    private static final String TENANT_TABLES_WITH_THEIR_RLS_STATE = """
        SELECT n.nspname AS schema_name,
               c.relname AS table_name,
               c.relrowsecurity,
               (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) AS policy_count
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE c.relkind IN ('r', 'p')
           AND n.nspname NOT IN ('pg_catalog', 'information_schema')
           AND EXISTS (SELECT 1
                         FROM pg_attribute a
                        WHERE a.attrelid = c.oid
                          AND a.attname = 'tenant_id'
                          AND a.attnum > 0
                          AND NOT a.attisdropped)
         ORDER BY n.nspname, c.relname
        """;

    @BeforeAll
    static void applyEveryMigration() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            everyMigrationInDeploymentOrder());
    }

    @Test
    void everyTableWithATenantIdEnforcesRowLevelSecurity() throws Exception {
        List<String> offenders = new ArrayList<>();
        int tenantTables = 0;

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(TENANT_TABLES_WITH_THEIR_RLS_STATE)) {
            while (rows.next()) {
                tenantTables++;
                String qualified = rows.getString("schema_name") + '.' + rows.getString("table_name");
                boolean enabled = rows.getBoolean("relrowsecurity");
                int policies = rows.getInt("policy_count");
                if (!enabled && policies == 0) {
                    offenders.add(qualified + " -- no ENABLE ROW LEVEL SECURITY and no policy");
                } else if (!enabled) {
                    offenders.add(qualified + " -- has " + policies + " policy/policies but RLS is OFF, so they are inert");
                } else if (policies == 0) {
                    offenders.add(qualified + " -- RLS is ON with no policy, which denies every row to app_role");
                }
            }
        }

        // The sweep must have found the platform. A mistyped catalog join would return zero rows
        // and the offender list would be empty -- a green test proving nothing, which is the exact
        // failure shape this class was written to stop. 70 is comfortably under the count at the
        // time of writing (80) and comfortably above anything a broken query would return.
        assertThat(tenantTables)
            .as("tables discovered carrying a tenant_id -- if this collapsed, the catalog query is wrong, not the schema")
            .isGreaterThanOrEqualTo(70);

        assertThat(offenders)
            .as("tenant tables with no working row-level security. Each one is readable across "
                + "tenants by app_role, which every module's migrations GRANT via ALTER DEFAULT "
                + "PRIVILEGES the moment the table is created. Fix by adding ENABLE ROW LEVEL "
                + "SECURITY and a policy in the NULLIF fail-closed form -- see "
                + "underwriting/V12__proposal_beneficiary_rls.sql")
            .isEmpty();
    }

    /**
     * Every {@code V*.sql}, module by module, in the order {@code scripts/migrate.sh} applies them.
     *
     * <p>The module order is read out of the script rather than restated here. A second copy would
     * be one more hand-maintained list, and a new module missing from it would go unswept -- which
     * is the failure this class exists to prevent, reproduced inside the prevention.
     */
    private static String[] everyMigrationInDeploymentOrder() throws IOException {
        String script = Files.readString(Path.of("scripts/migrate.sh"));
        Matcher modules = Pattern.compile("(?m)^MODULES=\"([^\"]+)\"").matcher(script);
        assertThat(modules.find())
            .as("scripts/migrate.sh no longer declares MODULES=\"...\" -- this test reads the "
                + "deployment order from there and cannot run without it")
            .isTrue();

        List<String> paths = new ArrayList<>();
        for (String module : modules.group(1).trim().split("\\s+")) {
            Path directory = Path.of("db-migrations", module);
            assertThat(Files.isDirectory(directory))
                .as("scripts/migrate.sh names module '%s' but db-migrations/%s does not exist", module, module)
                .isTrue();
            try (Stream<Path> files = Files.list(directory)) {
                files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    // Numeric, matching the script's own `sort -V`: a plain string sort puts V10
                    // before V2 and would apply an ALTER before its CREATE.
                    .sorted(Comparator.comparingInt(TenantTableRlsCoverageIntegrationTest::version))
                    .forEach(p -> paths.add("db-migrations/" + module + "/" + p.getFileName()));
            }
        }
        return paths.toArray(String[]::new);
    }

    private static int version(Path migration) {
        String name = migration.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
