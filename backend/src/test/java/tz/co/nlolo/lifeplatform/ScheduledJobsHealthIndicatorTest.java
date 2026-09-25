package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The check that makes a missing scheduled job visible -- the failure mode that is otherwise
 * silent, because a job that is not installed fails nothing.
 */
class ScheduledJobsHealthIndicatorTest {

    @SuppressWarnings("unchecked")
    private static ScheduledJobsHealthIndicator withRows(List<String> missing) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // The mapper yields the item when absent and null when present; hand back what it would.
        List<String> mapped = new java.util.ArrayList<>(missing);
        mapped.add(null);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn((List) mapped);
        return new ScheduledJobsHealthIndicator(jdbc);
    }

    @Test
    void everythingInstalledIsUp() {
        assertThat(withRows(List.of()).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aMissingJobIsDownAndNamesIt() {
        Health health = withRows(List.of("job commission-close")).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("missing")).isEqualTo(List.of("job commission-close"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDatabaseConfigureDbNeverTouchedIsUnknownNotDown() {
        // Every Testcontainers database: no function at all. UNKNOWN ranks below UP, so the suite's
        // own applications stay healthy -- this must not turn every test context DOWN.
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class)))
            .thenThrow(new BadSqlGrammarException("readiness", "SELECT", new SQLException("function does not exist")));
        assertThat(new ScheduledJobsHealthIndicator(jdbc).health().getStatus()).isEqualTo(Status.UNKNOWN);
    }

    /** A new post-migration script that configure-db.sh does not list would be installed nowhere
     * -- which is how four sweeps came to exist in no environment. The script refuses to run in
     * that state; this fails the build first. */
    @Test
    void everyPostMigrationScriptIsInstalledByConfigureDb() throws Exception {
        String installer = Files.readString(Path.of("scripts/configure-db.sh"));
        try (Stream<Path> files = Files.list(Path.of("db-migrations/_post-migration"))) {
            List<String> scripts = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sql")).toList();
            assertThat(scripts).isNotEmpty();
            for (String script : scripts) {
                assertThat(installer).as("scripts/configure-db.sh must install %s", script).contains(script);
            }
        }
    }

    /** And every job it installs is one the readiness check looks for -- otherwise a job that went
     * missing would go missing silently, which is the whole problem. */
    @Test
    void everyScheduledJobIsCheckedForByTheReadinessFunction() throws Exception {
        String readiness = Files.readString(Path.of("db-migrations/_post-migration/configure-ops-checks.sql"));
        try (Stream<Path> files = Files.list(Path.of("db-migrations/_post-migration"))) {
            for (Path file : files.toList()) {
                String sql = Files.readString(file);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("cron\\.schedule\\('([a-z-]+)'").matcher(sql);
                while (m.find()) {
                    assertThat(readiness).as("ops.platform_readiness() must check job %s (from %s)",
                        m.group(1), file.getFileName()).contains("'" + m.group(1) + "'");
                }
            }
        }
        assertThat(Arrays.asList("billing-sweep", "commission-close")).allSatisfy(j -> assertThat(readiness).contains(j));
    }
}
